package App::FoundryModules;

use v5.20;
use strict;
use warnings;
use utf8;
use Config;
use File::Path qw(make_path);
use Getopt::Long ();
use App::FoundryModules::Downloader;

our $VERSION = '0.10.0';

sub usage {
    return <<'USAGE';
Usage: foundry-modules [options] [modules.txt]

  -d, --dir DIRECTORY  Extract modules into DIRECTORY (default: current directory)
  -h, --help           Show this help
      --version        Show version

The links file defaults to modules.txt. Downloads and cache use the working directory.
USAGE
}

sub run {
    my ($class, @args) = @_;
    my ($help, $version);
    my $directory = '.';
    my $parser = Getopt::Long::Parser->new(config => [qw(no_auto_abbrev no_ignore_case permute)]);
    unless ($parser->getoptionsfromarray(\@args,
        'dir|d=s' => \$directory,
        'help|h' => \$help,
        'version' => \$version,
    )) {
        print STDERR usage();
        return 2;
    }
    if (defined $help) {
        print usage();
        return 0;
    }
    if (defined $version) {
        print "foundry-modules $VERSION\n";
        return 0;
    }
    if (@args > 1 || !length $directory) {
        print STDERR usage();
        return 2;
    }
    unless ($Config{useithreads}) {
        print STDERR "This application requires a Perl build with thread support.\n";
        return 2;
    }

    my $links_file = @args ? $args[0] : 'modules.txt';
    my $urls = App::FoundryModules::Downloader::load_urls_from_file($links_file);
    unless (@$urls) {
        print "❌ Empty links file! Add them to $links_file\n";
        return 0;
    }
    unless (eval { make_path($directory) unless -d $directory; 1 }) {
        print STDERR "Cannot create extraction directory: $directory\n";
        return 2;
    }

    print '🔗 ' . scalar(@$urls) . " links downloaded from $links_file\n";
    App::FoundryModules::Downloader::download_all($urls, $directory);
    App::FoundryModules::Downloader::unpack_all($directory);
    return 0;
}

1;

__END__

=head1 NAME

App::FoundryModules - Download and extract Foundry VTT modules

=head1 SYNOPSIS

    use App::FoundryModules;
    exit App::FoundryModules->run('--dir', 'modules', 'modules.txt');

=head1 DESCRIPTION

The application parses command-line options and runs the download and extraction
stages. App::FoundryModules::Downloader handles manifests, caching and worker
queues. App::FoundryModules::Archive validates ZIP entries and publishes extracted
directories only after successful extraction.

=head1 METHODS

=head2 run(@arguments)

Runs the application and returns its exit status: 0 on normal completion, or 2
for invalid arguments or an extraction directory that cannot be created. Individual
module failures are reported while the remaining queued modules are processed.

=cut
