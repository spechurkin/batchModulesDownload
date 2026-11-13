#!/usr/bin/env perl

use v5.20;
use strict;
use warnings;
use Config;
use FindBin;
use File::Basename qw(dirname);
use File::Copy qw(copy);
use File::Path qw(make_path);
use File::Spec;
use Getopt::Long qw(GetOptions);
use Digest::SHA;

my $root = File::Spec->rel2abs("$FindBin::Bin/..");
my $name = $^O eq 'MSWin32' ? 'foundry-modules.exe' : 'foundry-modules';
my $output = File::Spec->catfile($root, 'dist', $name);
GetOptions('output|o=s' => \$output) && !@ARGV
    or die "Usage: perl script/build.pl [--output FILE]\n";
die "Build requires a Perl interpreter with thread support.\n" unless $Config{useithreads};
eval { require PAR::Packer; PAR::Packer->VERSION('1.064'); 1 }
    or die "Install build dependencies: cpanm --with-develop --installdeps .\n";

$output = File::Spec->rel2abs($output);
make_path(dirname($output));

my @options = ('--output', $output, '--lib', File::Spec->catdir($root, 'lib'));
for my $module (qw(App::FoundryModules:: LWP::Protocol::http LWP::Protocol::https
    IO::Socket::SSL Net::SSLeay Mozilla::CA threads threads::shared)) {
    push @options, '--module', $module;
}
if ($^O eq 'MSWin32') {
    my $compiler_directory = File::Spec->catdir($Config{bin}, '..', '..', 'c', 'bin');
    for my $pattern (qw(libssl*.dll libcrypto*.dll libgcc_s*.dll libwinpthread*.dll zlib*.dll)) {
        my $glob = File::Spec->catfile($compiler_directory, $pattern);
        push @options, map { ('--link', $_) } glob($glob);
    }
}
push @options, File::Spec->catfile($root, 'bin', 'foundry-modules');

system {$^X} $^X, '-Mpp', '-e', 'pp->go()', '--', @options;
die "Executable build failed (status $?).\n" if $? != 0;
copy(File::Spec->catfile($root, 'modules.txt'), File::Spec->catfile(dirname($output), 'modules.txt'))
    or die "Cannot copy example modules.txt: $!\n";
copy(File::Spec->catfile($root, 'README.md'), File::Spec->catfile(dirname($output), 'README.md'))
    or die "Cannot copy README: $!\n";
open my $binary, '<:raw', $output or die "Cannot read executable: $!\n";
my $checksum = Digest::SHA->new(256)->addfile($binary)->hexdigest;
close $binary;
open my $checksums, '>', File::Spec->catfile(dirname($output), 'SHA256SUMS')
    or die "Cannot write checksum: $!\n";
my (undef, undef, $output_name) = File::Spec->splitpath($output);
print {$checksums} "$checksum  $output_name\n";
close $checksums or die "Cannot write checksum: $!\n";
print "Built $output\n";
