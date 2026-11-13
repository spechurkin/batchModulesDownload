package App::FoundryModules::Downloader;

use v5.20;
use strict;
use warnings;
use utf8;
use threads;
use threads::shared;
use Thread::Queue;
use Digest::SHA qw(sha256_hex);
use Encode qw(encode);
use File::Basename qw(basename);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempfile);
use HTTP::Request;
use JSON::PP;
use LWP::UserAgent;
use LWP::Protocol::http;
use App::FoundryModules::Archive qw(unpack_archive rename_file error_message);

my $cache_dir = 'cache';
my $download_dir = '.';

sub load_urls_from_file {
    my ($file_path) = @_;
    if (!-e $file_path) {
        print '⚠️ File not found: ' . File::Spec->rel2abs($file_path) . "\n";
        return [];
    }

    open my $input, '<:encoding(UTF-8)', $file_path or die "$file_path: $!\n";
    my @urls;
    while (my $line = <$input>) {
        $line =~ s/^\s+|\s+$//g;
        push @urls, $line if length($line) && $line !~ /^#/;
    }
    close $input;
    return \@urls;
}

sub normalize_url {
    my ($url) = @_;
    $url =~ s/^\s+|\s+$//g;

    if (index($url, 'dropbox.com') >= 0) {
        $url =~ s{https?://(www\.)?dropbox\.com}{https://dl.dropboxusercontent.com}g;
        $url =~ s/[?&](dl|raw)=\d//g;
    }

    if (index($url, 'github.com') >= 0 && index($url, '/blob/') >= 0) {
        $url =~ s{https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)}{https://raw.githubusercontent.com/$1/$2/$3/$4}g;
    } elsif (index($url, 'gist.github.com') >= 0) {
        $url =~ s{https?://gist\.github\.com/([^/]+)/([a-f0-9]+)}{https://gist.githubusercontent.com/$1/$2/raw}g;
    }

    return $url;
}

sub cache_file {
    my ($url) = @_;
    return File::Spec->catfile($cache_dir, sha256_hex(encode('UTF-8', $url)) . '.json');
}

sub request {
    my ($request, $callback) = @_;
    my $client = LWP::UserAgent->new(timeout => 60, max_redirect => 20);
    $client->agent('foundry-modules/0.10.0');
    local @LWP::Protocol::http::EXTRA_SOCK_OPTS = (Timeout => 20);

    my $response;
    for (1 .. 2) {
        $response = $callback
            ? $client->request($request, $callback)
            : $client->request($request);
        last unless ($response->header('Client-Warning') // '') eq 'Internal response';
        last unless $response->content =~ /Can't connect/;
    }
    if (($response->header('Client-Warning') // '') eq 'Internal response') {
        die $response->status_line . ': ' . $response->content . "\n";
    }
    return $response;
}

sub download_file {
    my ($url, $output) = @_;
    my $partial = "$output.part";
    my $output_stream;
    my $bytes = 0;
    my $ok = eval {
        open $output_stream, '>:raw', $partial or die "$partial: $!\n";
        my $response = request(HTTP::Request->new(GET => $url), sub {
            my ($chunk, $response) = @_;
            return unless $response->is_success;
            print {$output_stream} $chunk or die "$partial: $!\n";
            $bytes += length($chunk);
        });
        die 'Downloading error: ' . $response->code . "\n" unless $response->is_success;
        die $response->header('X-Died') . "\n" if $response->header('X-Died');
        die "Incomplete HTTP response\n" if $response->header('Client-Aborted');
        my $expected = $response->header('Content-Length');
        die "Incomplete HTTP response ($bytes of $expected bytes)\n"
            if defined($expected) && $expected =~ /^\d+$/ && $bytes != $expected;
        close $output_stream or die "$partial: $!\n";
        undef $output_stream;
        rename_file($partial, $output);
        1;
    };
    my $error = $@;
    close $output_stream if defined $output_stream;
    unlink $partial if -e $partial;
    die $error unless defined $ok;
}

sub valid_id {
    my ($id) = @_;
    return length($id) && $id ne '.' && $id ne '..'
        && $id !~ m{[\\/:*?"<>|\x00-\x1f\x7f]} && $id !~ /[. ]$/;
}

sub write_cache {
    my ($cache, $text) = @_;
    make_path($cache_dir);
    my ($output, $partial) = tempfile('manifest-XXXXXX', DIR => $cache_dir, SUFFIX => '.part', UNLINK => 0);
    my $ok = eval {
        binmode $output, ':encoding(UTF-8)';
        print {$output} $text or die "$partial: $!\n";
        close $output or die "$partial: $!\n";
        rename_file($partial, $cache);
        1;
    };
    my $error = $@;
    unlink $partial if -e $partial;
    die $error unless defined $ok;
}

sub fetch_module {
    my ($url) = @_;
    $url = normalize_url($url);
    my $cache = cache_file($url);

    my $text;
    if (-e $cache) {
        open my $input, '<:encoding(UTF-8)', $cache or die "$cache: $!\n";
        local $/;
        $text = <$input> // '';
        close $input;
    } else {
        my $request = HTTP::Request->new(GET => $url);
        $request->header('User-Agent' => 'foundry-modules/0.10.0');
        my $response = request($request);
        if (!$response->is_success) {
            print 'Error downloading module.json: ' . $response->code . " for $url\n";
            return undef;
        }

        my $content_type = $response->header('Content-Type') // '';
        $text = $response->decoded_content(default_charset => 'UTF-8') // '';
        $text =~ s/^\s+|\s+$//g;
        if ($content_type !~ /json/i && $text !~ /^\{/) {
            print "⚠️ $url did not return module.json (Content-Type=$content_type)\n";
            return undef;
        }
    }

    my $module;
    return undef if $text !~ /\S/;
    my $parsed = eval {
        my $data = JSON::PP->new->allow_nonref->decode($text);
        if (defined $data) {
            die "Expected a module object\n" unless ref($data) eq 'HASH';
            for my $field (qw(id name title download)) {
                die "Expected a scalar for $field\n" if ref $data->{$field};
            }
            $module = $data;
        }
        1;
    };
    if (!defined $parsed) {
        print "⚠️ Error parsing $url: " . error_message($@) . ".\n";
        return undef;
    }
    write_cache($cache, $text) if defined($module) && !-e $cache;
    return $module;
}

sub download_module {
    my ($url, $claimed, $output_directory) = @_;
    $output_directory //= '.';
    $url = normalize_url($url);
    my $module = fetch_module(normalize_url($url));
    return $url unless defined $module;

    my $id = $module->{id} // $module->{name};
    if (!defined $id) {
        print "Pass: no `id` and `name` for $url\n";
        return undef;
    }
    if (!defined $module->{download}) {
        print "Pass: no `download` for $id\n";
        return undef;
    }
    die "Invalid module id: $id\n" unless valid_id($id);
    if (defined $claimed) {
        lock(%{$claimed});
        return undef if $claimed->{lc $id}++;
    }

    my $archive_url = normalize_url($module->{download});
    my $archive_file = File::Spec->catfile($download_dir, "$id.zip");
    my $output_dir = File::Spec->catdir($output_directory, $id);
    my $cache = cache_file($url);

    if (-d $output_dir) {
        if (opendir my $dir, $output_dir) {
            my @entries = grep { $_ ne '.' && $_ ne '..' } readdir $dir;
            closedir $dir;
            if (@entries) {
                print "⏩ Pass: $id has already been unpacked\n";
                unlink $cache;
                return undef;
            }
        }
    }

    my $processed = eval {
        if (!-e $archive_file) {
            print "⬇️ Downloading archive: $archive_url → " . basename($archive_file) . "\n";
            download_file(normalize_url($archive_url), $archive_file);
        } else {
            print '✅ Archive already downloaded: ' . basename($archive_file) . "\n";
        }
        unlink $cache;
        print "✔️ Completed: $id (cache cleared)\n";
        1;
    };
    if (!defined $processed) {
        print "❌ Error during processing $id: " . error_message($@) . "\n";
        return $id;
    }
    return undef;
}

sub download_all {
    my ($urls, $output_directory) = @_;
    $output_directory //= '.';
    my %seen;
    my @urls = grep { !$seen{$_}++ } map { normalize_url($_) } @$urls;
    my %claimed :shared;
    my $queue = Thread::Queue->new(@urls);
    $queue->end();
    my @jobs = map {
        threads->create(sub {
            my @failed;
            while (defined(my $url = $queue->dequeue())) {
                my $failed;
                my $ok = eval { $failed = download_module($url, \%claimed, $output_directory); 1 };
                if (!defined $ok) {
                    print "❌ Error during processing $url: " . error_message($@) . "\n";
                    $failed = $url;
                }
                push @failed, $failed if defined $failed;
            }
            return \@failed;
        })
    } 1 .. (@urls < 5 ? scalar(@urls) : 5);

    my @failed_modules;
    for my $job (@jobs) {
        push @failed_modules, @{$job->join()};
    }
    cleanup_cache(@urls);

    if (@failed_modules) {
        print '⚠️ Unable to process modules: ' . join(', ', sort @failed_modules) . "\n";
    } else {
        print "🎉 All modules have been successfully processed.\n";
    }
}

sub unpack_all {
    my ($output_directory) = @_;
    $output_directory //= '.';
    my @archives;
    if (opendir my $dir, $download_dir) {
        @archives = map { File::Spec->catfile($download_dir, $_) }
            grep { /\.zip$/i && -f File::Spec->catfile($download_dir, $_) } readdir $dir;
        closedir $dir;
    }
    if (!@archives) {
        print "🎉 No archives to unpack\n";
        return;
    }

    my $total = scalar @archives;
    make_path($output_directory) unless -d $output_directory;
    print "📦 Starting to unpack $total archives...\n";
    my $queue = Thread::Queue->new(0 .. $#archives);
    $queue->end();
    my @jobs = map {
        threads->create(sub {
            my @results;
            while (defined(my $idx = $queue->dequeue())) {
                my $result = unpack_archive($archives[$idx], $idx, $total, $output_directory);
                push @results, [$idx, $result] if defined $result;
            }
            return \@results;
        })
    } 1 .. ($total < 5 ? $total : 5);

    my @results;
    push @results, @{$_->join()} for @jobs;
    my @successfully_unpacked = map { $_->[1] } sort { $a->[0] <=> $b->[0] } @results;
    if (@successfully_unpacked) {
        print '🧹 Deleting ' . scalar(@successfully_unpacked) . " successfully unpacked archives...\n";
        for my $zip (@successfully_unpacked) {
            if (-e $zip && !unlink $zip) {
                print '⚠️ Unable to delete ' . basename($zip) . "\n";
            }
        }
    }

    print "🎯 Unpacking complete. Total processed: $total, successfully: " . scalar(@successfully_unpacked) . "\n";
}

sub cleanup_cache {
    my (@urls) = @_;
    unlink cache_file($_) for @urls;
    rmdir $cache_dir if -d $cache_dir;
}

1;
