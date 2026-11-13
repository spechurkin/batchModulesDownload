requires 'perl', '5.020';
requires 'Digest::SHA';
requires 'Getopt::Long';
requires 'HTTP::Request';
requires 'IO::Uncompress::Unzip';
requires 'JSON::PP';
requires 'LWP::UserAgent';
requires 'LWP::Protocol::https';
requires 'Thread::Queue', '3.01';
requires 'threads';
requires 'threads::shared';

on 'test' => sub {
    requires 'Test::More', '0.98';
    requires 'HTTP::Daemon';
    requires 'IO::Compress::Zip';
};
