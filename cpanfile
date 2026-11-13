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

on 'develop' => sub {
    requires 'PAR::Packer', '1.064';
    requires 'Mozilla::CA';
    requires 'IO::Socket::SSL';
    requires 'Net::SSLeay';
};

on 'test' => sub {
    requires 'Test::More', '0.98';
    requires 'HTTP::Daemon';
    requires 'IO::Compress::Zip';
};
