use v5.20;
use strict;
use warnings;
use FindBin;
use File::Spec;
use File::Temp qw(tempdir);
use Cwd qw(getcwd);
use IPC::Open3;
use Symbol qw(gensym);
use Test::More;

my $script = File::Spec->rel2abs("$FindBin::Bin/../bin/foundry-modules");
my $original_directory = getcwd();
my $directory = tempdir(CLEANUP => 1);
chdir $directory or die "$directory: $!";

sub run_cli {
    my (@arguments) = @_;
    my $errors = gensym;
    my $pid = open3(my $input, my $output, $errors, $^X, $script, @arguments);
    close $input;
    local $/;
    my $stdout = <$output> // '';
    my $stderr = <$errors> // '';
    waitpid $pid, 0;
    return ($? >> 8, $stdout, $stderr);
}

for my $flag ('--help', '-h') {
    my ($status, $out, $err) = run_cli($flag);
    is($status, 0, "$flag exits successfully");
    like($out, qr/Usage: foundry-modules/, 'help shows installed executable name');
    is($err, '', 'no warnings');
    unlike($out, qr/Empty links file/, 'help does not run the pipeline');
}

my ($status, $out, $err) = run_cli('--version');
is($status, 0, 'version exits successfully');
like($out, qr/^foundry-modules \d+\.\d+\.\d+\s*$/, 'version follows distribution version');
is($err, '', 'version has no warnings');

for my $arguments (['--di', 'output'], ['--DIR', 'output'], ['one.txt', 'two.txt']) {
    ($status, $out, $err) = run_cli(@$arguments);
    is($status, 2, 'invalid CLI input rejected');
    like($err, qr/Usage:/, 'usage shown');
}

($status, $out, $err) = run_cli('--', '-links.txt');
is($status, 0, 'end of options allows filename beginning with dash');
like($out, qr/File not found:.*-links\.txt/, 'dash filename is a positional argument');

chdir $original_directory or die "$original_directory: $!";
done_testing;
