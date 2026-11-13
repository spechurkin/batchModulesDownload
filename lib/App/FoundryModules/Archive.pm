package App::FoundryModules::Archive;

use v5.20;
use strict;
use warnings;
use utf8;
use Exporter 'import';
use Encode qw(decode);
use Errno qw(EACCES EBUSY);
use File::Basename qw(basename dirname);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use IO::Uncompress::Unzip qw($UnzipError);

our @EXPORT_OK = qw(unpack_archive rename_file delete_recursively error_message);

sub rename_file {
    my ($source, $destination) = @_;
    for my $attempt (0 .. 5) {
        return if rename $source, $destination;
        die "Cannot move $source to $destination: $!\n"
            if $attempt == 5 || ($! != EACCES && $! != EBUSY);
        select undef, undef, undef, 0.1 * (2 ** $attempt);
    }
}

sub unzip {
    my ($zip_file, $dest_dir) = @_;
    my $zis = IO::Uncompress::Unzip->new($zip_file, Transparent => 0, Strict => 1);
    die "$UnzipError\n" unless defined $zis;

    my $status = 1;
    while ($status > 0) {
        my $entry = $zis->getHeaderInfo();
        my $name = decode('UTF-8', $entry->{Name});
        $name =~ tr{\\}{/};
        die "ZIP entry escapes destination: $name\n"
            if $name =~ m{^(?:/|[A-Za-z]:)} || grep { $_ eq '..' } split m{/}, $name;
        my $out_file = File::Spec->catfile($dest_dir, $name);
        if ($name =~ m{/$}) {
            make_path($out_file);
        } else {
            make_path(dirname($out_file));
            open my $output, '>:raw', $out_file or die "$out_file: $!\n";
            while (1) {
                my $length = $zis->read(my $buffer, 8192);
                die $zis->error . "\n" if $length < 0;
                last if $length == 0;
                print {$output} $buffer or die "$out_file: $!\n";
            }
            close $output or die "$out_file: $!\n";
        }
        $status = $zis->nextStream();
    }
    die $zis->error . "\n" if $status < 0;
    $zis->close();
}

sub unpack_archive {
    my ($archive, $idx, $total, $output_directory) = @_;
    $output_directory //= '.';
    my $id = basename($archive);
    $id =~ s/\.zip$//i;
    my $temp_dir = tempdir(".$id.unpack-XXXXXX", DIR => File::Spec->rel2abs($output_directory), CLEANUP => 0);
    my $final_dir = File::Spec->catdir($output_directory, $id);
    my $backup = "$temp_dir.backup";

    my $unpacked = eval {
        unzip($archive, $temp_dir);

        my $file_count = 0;
        find({ wanted => sub { ++$file_count if -f $_ }, no_chdir => 1 }, $temp_dir) if -d $temp_dir;
        die "Archive is empty or has not been unpacked.\n" if $file_count == 0;

        if (-e $final_dir) {
            rename_file($final_dir, $backup);
        }
        if (!eval { rename_file($temp_dir, $final_dir); 1 }) {
            my $error = $@;
            rename_file($backup, $final_dir) if -e $backup;
            die $error;
        }
        delete_recursively($backup) if -e $backup;
        print "✔️ [" . ($idx + 1) . "/$total] $id unpacked ($file_count files)\n";
        1;
    };
    if (!defined $unpacked) {
        my $error = $@;
        delete_recursively($temp_dir) if -e $temp_dir;
        print '❌ Error during unpacking ' . basename($archive) . ': ' . error_message($error) . "\n";
        return undef;
    }
    return $archive;
}

sub delete_recursively {
    my ($path) = @_;
    $path = File::Spec->rel2abs($path);
    # File::Path::remove_tree changes the process CWD, shared by Perl threads.
    if (-d $path && !-l $path) {
        opendir my $dir, $path or die "$path: $!\n";
        my @entries = grep { $_ ne '.' && $_ ne '..' } readdir $dir;
        closedir $dir;
        delete_recursively(File::Spec->catfile($path, $_)) for @entries;
        rmdir $path or die "$path: $!\n";
        return 1;
    }
    unlink $path or die "$path: $!\n";
    return 1;
}

sub error_message {
    my ($error) = @_;
    $error =~ s/\s+$//;
    return $error;
}

1;
