use v5.20;
use strict;
use warnings;
use Config;
use ExtUtils::MakeMaker;

die "A Perl build with thread support is required.\n" unless $Config{useithreads};

WriteMakefile(
    NAME => 'App::FoundryModules',
    VERSION_FROM => 'lib/App/FoundryModules.pm',
    ABSTRACT => 'Download and extract Foundry VTT modules',
    MIN_PERL_VERSION => '5.020',
    EXE_FILES => ['bin/foundry-modules'],
    PREREQ_PM => {
        'Digest::SHA' => 0,
        'Getopt::Long' => 0,
        'HTTP::Request' => 0,
        'IO::Uncompress::Unzip' => 0,
        'JSON::PP' => 0,
        'LWP::UserAgent' => 0,
        'LWP::Protocol::https' => 0,
        'Thread::Queue' => '3.01',
        'threads' => 0,
        'threads::shared' => 0,
    },
    TEST_REQUIRES => {
        'Test::More' => '0.98',
        'HTTP::Daemon' => 0,
        'IO::Compress::Zip' => 0,
    },
    META_MERGE => {
        'meta-spec' => { version => 2 },
        resources => {
            repository => {
                type => 'git',
                url => 'https://github.com/spechurkin/batchModulesDownload.git',
                web => 'https://github.com/spechurkin/batchModulesDownload/tree/experimental/perl',
            },
        },
    },
);
