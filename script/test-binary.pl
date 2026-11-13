#!/usr/bin/env perl

use v5.20;
use strict;
use warnings;
use FindBin;
use File::Copy qw(copy);
use File::Spec;
use File::Temp qw(tempdir);

my $binary = shift @ARGV;
die "Usage: perl script/test-binary.pl EXECUTABLE\n" unless defined $binary && !@ARGV && -f $binary;
$binary = File::Spec->rel2abs($binary);
my $directory = tempdir(CLEANUP => 1);
my $name = $^O eq 'MSWin32' ? 'foundry-modules.exe' : 'foundry-modules';
my $isolated = File::Spec->catfile($directory, $name);
copy($binary, $isolated) or die "Cannot copy executable: $!\n";
chmod 0755, $isolated or die "Cannot make executable runnable: $!\n";
$ENV{FOUNDRY_MODULES_EXE} = $isolated;
chdir "$FindBin::Bin/.." or die "Cannot enter project directory: $!\n";
require Test::Harness;
Test::Harness::runtests('t/cli.t', 't/downloader.t');
