use strict;
use warnings;
use utf8;
use threads;
use threads::shared;
use Cwd qw(getcwd);
use FindBin;
use lib "$FindBin::Bin/../lib";
use File::Path qw(make_path);
use File::Copy qw(copy);
use File::Spec;
use File::Temp qw(tempdir);
use HTTP::Daemon;
use HTTP::Response;
use IO::Compress::Zip qw($ZipError);
use IPC::Open3;
use JSON::PP qw(encode_json);
use LWP::UserAgent;
use Symbol qw(gensym);
use Test::More;

binmode STDOUT, ':encoding(UTF-8)';
binmode STDERR, ':encoding(UTF-8)';

my $original_dir = getcwd();
my $test_dir = tempdir(CLEANUP => 1);
chdir $test_dir or die "$test_dir: $!";
require App::FoundryModules::Downloader;

my $main = File::Spec->rel2abs("$FindBin::Bin/../bin/foundry-modules");
my $zip = '';
my $writer = IO::Compress::Zip->new(\$zip, Name => 'nested/module.json') or die $ZipError;
print {$writer} '{"id":"inside"}';
$writer->newStream(Name => 'payload.bin') or die $ZipError;
print {$writer} "\x00\xff\r\n";
$writer->close() or die $ZipError;
undef $writer;

my $daemon = HTTP::Daemon->new(LocalAddr => '127.0.0.1', LocalPort => 0)
    or die "Cannot start local HTTP server: $!";
my $base = $daemon->url;
my %hits :shared;
my %routes = (
    '/module.json' => [200, 'application/json', encode_json({id => 'sample', title => 'Sample', download => "${base}module.zip"})],
    '/fallback.json' => [200, 'text/plain', encode_json({name => 'fallback', download => "${base}module.zip"})],
    '/no-id.json' => [200, 'application/json', '{"title":"No ID"}'],
    '/no-download.json' => [200, 'application/json', '{"id":"no-download"}'],
    '/failed.json' => [200, 'application/json', encode_json({id => 'failed', download => "${base}missing.zip"})],
    '/html' => [200, 'text/html', '<html>Not JSON</html>'],
    '/invalid.json' => [200, 'application/json', '{broken'],
    '/invalid-field.json' => [200, 'application/json', '{"id":false}'],
    '/null.json' => [200, 'application/json', 'null'],
    '/fields.json' => [200, 'application/json', '{"id":0,"name":"fallback","unknown":[1,2]}'],
    '/module.zip' => [200, 'application/zip', $zip],
    '/empty.zip' => [200, 'application/zip', ''],
    '/redirect.json' => [302, 'text/plain', ''],
);

my $server = threads->create(sub {
    while (my $connection = $daemon->accept()) {
        my $request = $connection->get_request();
        if (!$request) {
            $connection->close();
            next;
        }
        my $path = $request->uri->path;
        { lock %hits; ++$hits{$path} }
        my ($code, $type, $body) = @{$routes{$path} // [404, 'text/plain', 'Missing']};
        my $response = HTTP::Response->new($code);
        $response->header('Content-Type' => $type, 'Connection' => 'close');
        $response->header('Location' => "${base}module.json") if $path eq '/redirect.json';
        $response->content($body);
        $connection->send_response($response);
        $connection->close();
        last if $path eq '/stop';
    }
});

sub write_file {
    my ($name, $content) = @_;
    open my $output, '>:raw', $name or die "$name: $!";
    print {$output} $content;
    close $output or die "$name: $!";
}

sub read_file {
    my ($name) = @_;
    open my $input, '<:raw', $name or die "$name: $!";
    local $/;
    return <$input>;
}

sub run_command {
    my (@command) = @_;
    my $errors = gensym;
    my $pid = open3(my $input, my $output, $errors, @command);
    close $input;
    local $/;
    my $stdout = <$output> // '';
    my $stderr = <$errors> // '';
    waitpid $pid, 0;
    return ($? >> 8, $stdout, $stderr);
}

sub run_perl {
    return run_command($^X, @_);
}

sub run_executable {
    my ($binary, @args) = @_;
    local %ENV = %ENV;
    delete @ENV{qw(PERL5LIB PERLLIB PERL5OPT)};
    $ENV{PATH} = $^O eq 'MSWin32' ? "$ENV{SystemRoot}/System32;$ENV{SystemRoot}" : '/usr/bin:/bin';
    return run_command($binary, @args);
}

sub run_main {
    if ($ENV{FOUNDRY_MODULES_EXE}) {
        my $local_binary = File::Spec->rel2abs('foundry-modules' . ($^O eq 'MSWin32' ? '.exe' : ''));
        copy($ENV{FOUNDRY_MODULES_EXE}, $local_binary) or die "Cannot stage executable: $!";
        chmod 0755, $local_binary or die "Cannot set executable permissions: $!";
        return run_executable($local_binary, @_);
    }
    return run_perl($main, @_);
}

sub in_directory {
    my ($name, $test) = @_;
    my $directory = File::Spec->catdir($test_dir, $name);
    make_path($directory);
    chdir $directory or die "$directory: $!";
    $test->();
    chdir $test_dir or die "$test_dir: $!";
}

subtest 'URL loading and provider normalization' => sub {
    write_file('links.txt', "  # comment\r\n\r\n https://example.com/module.json \r\nhttps://example.com/second\n");
    is_deeply(App::FoundryModules::Downloader::load_urls_from_file('links.txt'),
        ['https://example.com/module.json', 'https://example.com/second'], 'trim and ignore blank/comment lines');
    is_deeply(App::FoundryModules::Downloader::load_urls_from_file('missing.txt'), [], 'missing file returns empty list');
    is(App::FoundryModules::Downloader::normalize_url(' https://github.com/user/repo/blob/main/module.json '),
        'https://raw.githubusercontent.com/user/repo/main/module.json', 'GitHub blob');
    is(App::FoundryModules::Downloader::normalize_url('https://gist.github.com/user/abc123'),
        'https://gist.githubusercontent.com/user/abc123/raw', 'Gist');
    is(App::FoundryModules::Downloader::normalize_url('https://www.dropbox.com/scl/fi/a/module.json?rlkey=key&dl=1'),
        'https://dl.dropboxusercontent.com/scl/fi/a/module.json?rlkey=key', 'Dropbox');
    is(App::FoundryModules::Downloader::normalize_url('https://dropbox.com/a?dl=1&rlkey=key'),
        'https://dl.dropboxusercontent.com/a&rlkey=key', 'preserve original query replacement behavior');
    my $ascii = App::FoundryModules::Downloader::cache_file('abc');
    like($ascii, qr/[a-f0-9]{64}\.json$/, 'SHA-256 cache filename');
    is($ascii, App::FoundryModules::Downloader::cache_file('abc'), 'stable cache filename');
    isnt($ascii, App::FoundryModules::Downloader::cache_file("😀"), 'distinct Unicode URL cache');
};

subtest 'fetch, cache, JSON validation and HTTP redirects' => sub {
    my $module = App::FoundryModules::Downloader::fetch_module("${base}module.json");
    is(ref($module), 'HASH', 'manifest is a native hash reference');
    is($module->{id}, 'sample', 'manifest ID');
    ok(-f App::FoundryModules::Downloader::cache_file("${base}module.json"), 'manifest cached');
    App::FoundryModules::Downloader::fetch_module("${base}module.json");
    is($hits{'/module.json'}, 1, 'second fetch reads cache');
    is(App::FoundryModules::Downloader::fetch_module("${base}redirect.json")->{id}, 'sample', 'follow redirect');
    is(App::FoundryModules::Downloader::fetch_module("${base}fallback.json")->{name}, 'fallback', 'JSON body accepted with text content type');
    is(App::FoundryModules::Downloader::fetch_module("${base}fields.json")->{id}, '0', 'numeric ID preserved');
    is(App::FoundryModules::Downloader::fetch_module("${base}fields.json")->{name}, 'fallback', 'scalar name preserved');
    ok(!defined App::FoundryModules::Downloader::fetch_module("${base}html"), 'reject HTML');
    ok(!defined App::FoundryModules::Downloader::fetch_module("${base}invalid.json"), 'reject malformed JSON');
    ok(!defined App::FoundryModules::Downloader::fetch_module("${base}invalid-field.json"), 'reject reference-valued manifest fields');
    ok(!defined App::FoundryModules::Downloader::fetch_module("${base}null.json"), 'null manifest skipped');
    ok(!defined App::FoundryModules::Downloader::fetch_module("${base}missing.json"), 'HTTP failure skipped');
};

subtest 'CLI download and unpack pipeline' => sub {
    in_directory('pipeline', sub {
        write_file('modules.txt', "${base}module.json\n${base}fallback.json\n");
        my ($status, $out, $err) = run_main();
        is($status, 0, 'default modules.txt run succeeds') or diag $err;
        like($out, qr/2 links downloaded from .*modules.txt/, 'link count');
        is(read_file('sample/nested/module.json'), '{"id":"inside"}', 'nested entry extracted');
        is(read_file('sample/payload.bin'), "\x00\xff\r\n", 'binary bytes preserved');
        ok(-f 'fallback/payload.bin', 'name used when ID absent');
        ok(!-e 'sample.zip' && !-e 'fallback.zip', 'successful archives deleted');
        ok(!-e 'cache', 'cache directory removed');
        ok(!-e 'sample_tmp', 'temporary directory renamed');
        like($out, qr/Total processed: 2, successfully: 2/, 'unpack summary');
        is($err, '', 'no runtime warnings');

        my $downloads = $hits{'/module.zip'};
        ($status, $out, $err) = run_main();
        is($status, 0, 'repeat run succeeds');
        like($out, qr/sample has already been unpacked/, 'skip populated directory');
        is($hits{'/module.zip'}, $downloads, 'skip archive request on repeat');
        like($out, qr/No archives to unpack/, 'nothing to unpack');
    });
};

subtest 'portable defaults and explicit paths from a different working directory' => sub {
    plan skip_all => 'Run script/test-binary.pl to test portable executable defaults'
        unless $ENV{FOUNDRY_MODULES_EXE};
    in_directory('portable-launch', sub {
        make_path('application');
        my $binary = File::Spec->rel2abs('application/foundry-modules' . ($^O eq 'MSWin32' ? '.exe' : ''));
        copy($ENV{FOUNDRY_MODULES_EXE}, $binary) or die "Cannot stage executable: $!";
        chmod 0755, $binary or die "Cannot set executable permissions: $!";
        write_file('application/modules.txt', "${base}module.json\n");
        write_file('modules.txt', "${base}failed.json\n");

        my ($status, $out, $err) = run_executable($binary);

        is($status, 0, 'no-argument launch succeeds from another directory') or diag $out, $err;
        ok(-f 'application/sample/payload.bin', 'default destination is beside executable');
        ok(!-e 'sample', 'working directory does not receive default extraction');
        unlike($out, qr/Unable to process modules/, 'read modules.txt beside executable');
        is($err, '', 'standalone launch has no warnings');

        write_file('custom list.txt', "${base}fallback.json\n");
        for my $option ('--modules', '-m', 'positional') {
            my @input = $option eq 'positional' ? ('custom list.txt') : ($option, 'custom list.txt');
            my $output = "custom output $option";
            ($status, $out, $err) = run_executable($binary, @input, '-d', $output);
            is($status, 0, "$option input override succeeds") or diag $out, $err;
            ok(-f "$output/fallback/payload.bin", 'relative output is resolved from terminal directory');
            is($err, '', 'override run has no warnings');
        }
    });
};

subtest 'CLI extraction directory options and repeat runs' => sub {
    for my $option ('--dir', '-d', '--dir=', 'absolute', 'cache') {
        in_directory('directory-' . $option, sub {
            write_file('links.txt', "${base}module.json\n");
            write_file('modules.txt', "${base}module.json\n");
            make_path('sample');
            write_file('sample/original.txt', 'keep');
            my $destination = $option eq 'cache' ? 'cache/modules' : 'nested/extracted modules';
            my @args = $option eq '-d' ? ('-d', $destination, 'links.txt')
                : $option eq '--dir=' ? ("--dir=$destination")
                : ('links.txt', '--dir', $option eq 'absolute' ? File::Spec->rel2abs($destination) : $destination);
            my $downloads = $hits{'/module.zip'};

            my ($status, $out, $err) = run_main(@args);

            is($status, 0, "$option succeeds") or diag $err;
            is(read_file("$destination/sample/payload.bin"), "\x00\xff\r\n", 'extract into selected directory');
            is(read_file('sample/original.txt'), 'keep', 'working directory module preserved');
            ok(!-e 'sample/payload.bin', 'no extraction into working directory');
            ok(!-e 'sample.zip', 'successful archive deleted');
            is($hits{'/module.zip'}, $downloads + 1, 'download ignores module in working directory');
            is($err, '', 'no runtime warnings');

            ($status, $out, $err) = run_main(@args);

            is($status, 0, 'repeat succeeds');
            like($out, qr/sample has already been unpacked/, 'skip module in selected directory');
            is($hits{'/module.zip'}, $downloads + 1, 'no repeat download');
        });
    }
};

subtest 'CLI rejects invalid extraction options before downloading' => sub {
    in_directory('invalid-options', sub {
        write_file('modules.txt', "${base}module.json\n");
        for my $option ('--dir', '-d', '--dir=', '--unknown') {
            my $requests = $hits{'/module.json'};
            my ($status, $out, $err) = run_main($option);
            is($status, 2, "$option fails");
            like($err, qr/Usage:/, 'usage shown');
            is($hits{'/module.json'}, $requests, 'no network request');
        }
        write_file('output', 'keep');
        my ($status, $out, $err) = run_main('-d', 'output');
        is($status, 2, 'file is not an extraction directory');
        like($err, qr/Cannot create extraction directory/, 'directory error shown');
        is(read_file('output'), 'keep', 'file preserved');
    });
};

subtest 'CLI extracts an existing working directory archive elsewhere' => sub {
    in_directory('existing-archive-output', sub {
        write_file('modules.txt', "${base}module.json\n");
        write_file('sample.zip', $zip);
        my $downloads = $hits{'/module.zip'};
        my ($status, $out, $err) = run_main('-d', 'output');
        is($status, 0, 'existing archive run succeeds') or diag $err;
        is(read_file('output/sample/payload.bin'), "\x00\xff\r\n", 'existing archive extracted elsewhere');
        ok(!-e 'sample.zip', 'existing archive deleted after success');
        is($hits{'/module.zip'}, $downloads, 'no archive download');
    });
};

subtest 'existing archives, failed downloads, missing fields and invalid archives' => sub {
    in_directory('failures', sub {
        write_file('links.txt', join("\n", map { "$base$_" }
            qw(module.json no-id.json no-download.json failed.json html invalid.json)) . "\n");
        write_file('sample.zip', $zip);
        write_file('orphan.zip', $zip);
        write_file('broken.zip', 'not a zip');
        my $directory_zip = '';
        IO::Compress::Zip::zip(\'', \$directory_zip, Name => 'empty/') or die $ZipError;
        write_file('empty.zip', $directory_zip);
        make_path('sample_tmp');
        write_file('sample_tmp/stale', 'stale');
        make_path('orphan');
        write_file('orphan/old', 'old');

        my $downloads = $hits{'/module.zip'};
        my ($status, $out, $err) = run_main('links.txt');
        is($status, 0, 'recoverable errors do not abort CLI') or diag $err;
        like($out, qr/Archive already downloaded: sample.zip/, 'reuse ZIP');
        is($hits{'/module.zip'}, $downloads, 'cached ZIP avoids request');
        like($out, qr/Pass: no `id` and `name`/, 'missing ID and name');
        like($out, qr/Pass: no `download` for no-download/, 'missing download');
        like($out, qr/Downloading error: 404/, 'archive HTTP status');
        like($out, qr/Unable to process modules: failed/, 'failed ID summary');
        ok(!-e 'failed.zip', 'HTTP error does not create ZIP');
        ok(-f 'sample/payload.bin' && -f 'orphan/payload.bin', 'unpack both listed and unlisted ZIPs');
        ok(!-e 'sample/stale', 'stale temporary contents removed');
        ok(!-e 'orphan/old', 'existing destination replaced when unpacking');
        ok(-f 'broken.zip' && -f 'empty.zip', 'failed ZIPs retained');
        like($out, qr/Total processed: 4, successfully: 2/, 'mixed unpack summary');
        ok(!-e 'cache', 'cache cleared even for recoverable failures');
        is($err, '', 'no runtime warnings');
    });
};

subtest 'missing and empty link files stop before unpacking' => sub {
    in_directory('empty-input', sub {
        write_file('orphan.zip', $zip);
        my ($status, $out, $err) = run_main('missing.txt');
        is($status, 0, 'missing file exits normally');
        like($out, qr/File not found:.*missing.txt/, 'missing file warning');
        like($out, qr/Empty links file! Add them to .*missing.txt/, 'empty links warning');
        ok(-f 'orphan.zip' && !-d 'orphan', 'no unpack for empty URL list');
        write_file('modules.txt', "# comment\n \n");
        ($status, $out, $err) = run_main();
        is($status, 0, 'comment-only file exits normally');
        like($out, qr/Empty links file! Add them to .*modules.txt/, 'default empty links message');
    });
};

subtest 'downloads use at most five workers and deduplicate URLs' => sub {
    in_directory('download-concurrency', sub {
        write_file('check-downloads.pl', q{
            use strict;
            use warnings;
            use threads;
            use threads::shared;
            use App::FoundryModules::Downloader;
            binmode STDOUT, ':encoding(UTF-8)';
            my $active :shared = 0;
            my $peak :shared = 0;
            my $count :shared = 0;
            no warnings 'redefine';
            *App::FoundryModules::Downloader::download_module = sub {
                { lock $active; ++$active; $peak = $active if $active > $peak }
                select undef, undef, undef, 0.4;
                { lock $count; ++$count }
                { lock $active; --$active }
                return undef;
            };
            App::FoundryModules::Downloader::download_all([map { "http://localhost/module$_.json" } (1 .. 9, 1 .. 9)]);
            print "PEAK=$peak ACTIVE=$active COUNT=$count\n";
        });
        my ($status, $out, $err) = run_perl('-I', "$FindBin::Bin/../lib", 'check-downloads.pl');
        is($status, 0, 'download worker process succeeds') or diag $err;
        my ($peak, $active, $count) = $out =~ /PEAK=(\d+) ACTIVE=(\d+) COUNT=(\d+)/;
        cmp_ok($peak, '>', 1, 'multiple downloads overlap');
        cmp_ok($peak, '<=', 5, 'no more than five downloads overlap');
        is($active, 0, 'all downloads completed');
        is($count, 9, 'duplicate URLs queued only once');
    });
};

subtest 'failed transfers never publish partial archives' => sub {
    in_directory('interrupted', sub {
        write_file('check-partial.pl', q{
            use strict;
            use warnings;
            use App::FoundryModules::Downloader;
            use HTTP::Response;
            no warnings 'redefine';
            *App::FoundryModules::Downloader::request = sub {
                my ($request, $callback) = @_;
                my $response = HTTP::Response->new(200);
                $response->header('Content-Length' => 100);
                $callback->('partial', $response);
                return $response;
            };
            my $ok = eval { App::FoundryModules::Downloader::download_file('http://localhost/archive.zip', 'module.zip'); 1 };
            die 'Incomplete response accepted' if $ok;
            die 'Partial archive published' if -e 'module.zip' || -e 'module.zip.part';
            print "PARTIAL_CLEANED\n";
        });
        my ($status, $out, $err) = run_perl('-I', "$FindBin::Bin/../lib", 'check-partial.pl');
        is($status, 0, 'incomplete HTTP body rejected') or diag $out, $err;
        like($out, qr/PARTIAL_CLEANED/, 'ZIP and partial file absent');
    });
};

subtest 'unsafe ZIP preserves destination and module ids cannot collide with staging' => sub {
    in_directory('safe-unpack', sub {
        my $unsafe = '';
        my $writer = IO::Compress::Zip->new(\$unsafe, Name => 'safe.txt') or die $ZipError;
        print {$writer} 'safe';
        $writer->newStream(Name => '../escape.txt') or die $ZipError;
        print {$writer} 'escape';
        $writer->close() or die $ZipError;
        write_file('unsafe.zip', $unsafe);
        make_path('unsafe');
        write_file('unsafe/original.txt', 'keep');
        write_file('module.zip', $zip);
        write_file('module_tmp.zip', $zip);
        write_file('links.txt', "${base}no-id.json\n");

        my ($status, $out, $err) = run_main('links.txt');

        is($status, 0, 'other archives complete after unsafe entry') or diag $out, $err;
        like($out, qr/Total processed: 3, successfully: 2/, 'failed extraction excluded from success count');
        is(read_file('unsafe/original.txt'), 'keep', 'previous destination preserved');
        ok(!-e 'unsafe/safe.txt' && !-e 'escape.txt', 'partial extraction not published');
        ok(-f 'unsafe.zip', 'failed archive retained');
        ok(-f 'module/payload.bin' && -f 'module_tmp/payload.bin', 'module ids cannot collide with staging');
        is($err, '', 'no runtime warnings');
    });
};

subtest 'unpacking runs concurrently with at most five workers' => sub {
    in_directory('concurrency', sub {
        write_file("module$_.zip", $zip) for 1 .. 9;
        write_file('check-concurrency.pl', q{
            use strict;
            use warnings;
            use threads;
            use threads::shared;
            use File::Path qw(make_path);
            use App::FoundryModules::Downloader;
            binmode STDOUT, ':encoding(UTF-8)';
            my $active :shared = 0;
            my $peak :shared = 0;
            no warnings 'redefine';
            *App::FoundryModules::Archive::unzip = sub {
                my ($archive, $destination) = @_;
                {
                    lock $active;
                    ++$active;
                    $peak = $active if $active > $peak;
                }
                select undef, undef, undef, 0.2;
                make_path($destination);
                open my $output, '>', "$destination/payload" or die $!;
                print {$output} 'data';
                close $output;
                { lock $active; --$active }
            };
            App::FoundryModules::Downloader::unpack_all();
            print "PEAK=$peak ACTIVE=$active\n";
        });
        my ($status, $out, $err) = run_perl('-I', "$FindBin::Bin/../lib", 'check-concurrency.pl');
        is($status, 0, 'instrumented unpack process succeeds') or diag $err;
        my ($peak, $active) = $out =~ /PEAK=(\d+) ACTIVE=(\d+)/;
        diag($out, $err) unless defined $peak;
        cmp_ok($peak, '>', 1, 'multiple extractions overlap');
        cmp_ok($peak, '<=', 5, 'no more than five extractions overlap');
        is($active, 0, 'all workers completed');
        is(scalar(grep { -f "module$_/payload" && !-e "module$_.zip" } 1 .. 9), 9,
            'all queued archives processed and deleted');
    });
};

LWP::UserAgent->new(timeout => 5)->get("${base}stop");
$server->join();
$daemon->close();
chdir $original_dir or die "$original_dir: $!";
done_testing();
