#!e:/Perl/bin/perl.exe -w
##!/usr/local/bin/perl -w
##!/usr/bin/perl -w
#####
# web interface to download the copy of database used for Mascot search 
# to CPAS.  This code is developed for CPAS's MS2 Mascot Support
#
# This script needs to be copied to <mascot directory>/cgi and its top line
# Perl executable path set correctly.  The script should be has its 
# execution attribute set on (chmod a+rx labkeydbmgmt.pl).
#
# Perl Modeul Digest::SHA or Digest::SHA1 must be installed 
# in order for script to function
# You need to have a build system in order to install the module.
#
# You can install the module by issuing command:
#     perl -MCPAN -e"install Digest::SHA"
# on Linux/Unix
# For ActivePerl, you can use the command above, or via ppm.
# If ppm is used, you can issue the command:
#     install Digest-SHA
# at the ppm prompt. 
# 
# 
#   o FUTURE ENHANCEMENTS
#     - please speak to authors
#
#   o PENDINGS
#     - use wrapC version of SHA-1none at the moment
#
#   o AUTHORS
#     - Wong Chee Hong
#
#   o HISTORY
#     2007-Feb-18  Wong Chee-Hong  Initial Version
#                  Bioinformatics Institute
#     2007-Mar-15  Wong Chee-Hong  Mechanism to try using Digest::SHA1, Digest::SHA or Digest::SHA::PurePerl
#	  2008-Mar-13  Bill Nelson	   Added get enzymes method.
#####

$|++;

use strict;
use Getopt::Long;
use CGI;
my $shaInitialised = undef;
if (!defined $shaInitialised) {
  $shaInitialised = eval { require Digest::SHA1 };
  $shaInitialised = "Digest::SHA1->new()" if (defined $shaInitialised);
}
if (!defined $shaInitialised) {
  $shaInitialised = eval { require Digest::SHA };
  $shaInitialised = "Digest::SHA->new()" if (defined $shaInitialised);
}
if (!defined $shaInitialised) {
  # if you have problem installing Digest::SHA1 or Digest::SHA, you can use Digest::SHA::PurePerl
  # But doing so, it will increase the execution greatly (e.g. over 150x slower)
  $shaInitialised = eval { require Digest::SHA::PurePerl };
  $shaInitialised = "Digest::SHA::PurePerl->new()" if (defined $shaInitialised);
}

my $q = new CGI;
my $command = $q->param ("cmd");
if (!defined $command) {
  $command = '';
  $q->param (cmd=>$command);
}

if (defined $q->request_method()) {
  if (!defined $shaInitialised) {
    print $q->header(-type=>'text/html');
    print 'STATUS=INTERNAL ERROR', "\n";
    print 'DETAIL=Digest::SHA module not installed properly. See script header for installation instruction. ', (defined $@ ? $@ : ''), "\n";
  } 
  # handle web based request
  elsif ($command eq "dbinfo") {
    getDBInfoWeb();
  } elsif ($command eq "downloaddb") {
    downloadDB();
  } elsif ($command eq "getenzymes") {
    getEnzymes();
  } else {
    print $q->header(-type=>'text/html');
    print 'STATUS=UNRECOGNISED COMMAND';
  }
} else {
  if (!defined $shaInitialised) {
    die ('ERROR: Digest::SHA module not installed properly.  See script header for installation instruction. '.(defined $@ ? "\n".$@ : '')."\n");
  } 
  
  # handle the command line
  my %runOptions = ();
  $runOptions{db} = '';
  $runOptions{release} = '';
  unless ( GetOptions (
    "db:s" => \$runOptions{db}
    ,"release:s" => \$runOptions{release}
    ) ) {
    die ("Please specify <db> and <release>.\n");
  }

  if ($runOptions{db} eq '' || $runOptions{release} eq '') {
    die ("Please specify <db> and <release>.\n");
  }

  getDBInfoImpl($runOptions{db}, $runOptions{release});
}

close STDOUT;

exit 0;

sub getReleaseFullPath {
  my ($db, $release) = @_;
  
  if (!open(CONFIGFILE, "../config/mascot.dat")) {
    return (-1, 'Fail to open Mascot config file');
  }

  my $status = -1;
  my $statusMsg = '';
  
  my $inDatabaseSection=0;    
  while (<CONFIGFILE>) {
    if (/^databases[\s\n]/i) {
      $inDatabaseSection=1;
    } elsif (/^(cluster|cron|options|parse|taxonomy_\d+|unigene|www)[\s\n]/i){
      $inDatabaseSection=0;
    } elsif (/^end[\s\n]/i){
      $inDatabaseSection=0;
    } elsif (1==$inDatabaseSection) {
      if (/^$db\s+/i) {
        my @lineParts=split('\s+');
        if (defined $lineParts[1]) {
          my @pathParts=split('/', $lineParts[1]);
          
          #<path prefix>/release.fasta
          #<path prefix>/current/release.fasta
          #<path prefix>/old/release.fasta
          #IPI_Human_20060501	C:/Mascot/sequence/test2/IPI_human_20060501*.fasta	AA	0	0	1	1	1	0	0	21	7	0	0
          my $numParts = scalar(@pathParts);
          if ($numParts>0) {
            splice @pathParts, $numParts-1;
            $numParts--;
            if ($pathParts[$numParts-1] =~ /^current$/i) {
              # auto-update option used, so we have to check "current" and "old"
              my $releaseCurrentFullPath = join('/', @pathParts) . '/' . $release;
              if (-f $releaseCurrentFullPath) {
                $status = 0;
                $statusMsg = $releaseCurrentFullPath;
              } else {
                splice @pathParts, $numParts-1;
                my $releaseOldFullPath = join('/', @pathParts) . '/old/' . $release;
                if (-f $releaseOldFullPath) {
                  $status = 0;
                  $statusMsg = $releaseOldFullPath;
                } else {
                  $status = -5;
                  $statusMsg = $releaseCurrentFullPath.' and '.$releaseOldFullPath.' not found on file system.';
                }
              }
                            
            } else {
              # no auto-update option used, only single path is available
              my $releaseFullPath = join('/', @pathParts) . '/' . $release;
              if (-f $releaseFullPath) {
                $status = 0;
                $statusMsg = $releaseFullPath;
              } else {
                $status = -5;
                $statusMsg = $releaseFullPath.' not found on file system.';
              }
            }
          }
          
        } else {
          $status = -4;
          $statusMsg = $release.' of '.$db.' not recorded in Mascot config.';
        }
        last;
      }
    } else {
      # do nothing
    }
  }
  
  if (!close(CONFIGFILE)) {
    if (-1==$status) {
      $status = -2;
      $statusMsg = 'Fail to close Mascot config file';
    }
  }

  if (-1==$status) {
    $status = -3;
    $statusMsg = $release.' of '.$db.' not found in Mascot config.';
  }
  
  return ($status, $statusMsg);
}

sub hashFileContents {
  my ($fName) = @_;

  my $sha = undef;
  my $command = '$sha = ' . $shaInitialised;
  my $result = eval $command;  warn $@ if $@;
  open (my $fh, "<$fName")
    or _bail("Open failed");
  binmode($fh);
  $sha->addfile($fh);
  close($fh);
  my $digest = $sha->hexdigest;
  return $digest;
}

sub getCachedFileChecksums {
  my ($fName) = @_;
  
  if (! -f $fName) {
    return ('', 0, 0);
  }
  
  my $fHashName = $fName.'.hash';
  
  my ($cachedHash, $cachedSize, $cachedMtime) = ('', 0, 0);
  if (-f $fHashName) {
    # read the checksums
    if (open (INFILE, "<$fHashName")) {
      while (<INFILE>) {
        if (/^hash=/i) {
          my $rightpart = $';
          ($cachedHash) = $rightpart =~ /([0-9a-f]+)/;
        } elsif (/^filesize=/i) {
          my $rightpart = $';
          ($cachedSize) = $rightpart =~ /(\d+)/;
        } elsif (/^timestamp=/i) {
          my $rightpart = $';
          ($cachedMtime) = $rightpart =~ /(\d+)/;
        }
      }
      close INFILE;
    }
    
    my ($dev,$ino,$mode,$nlink,$uid,$gid,$rdev,$size,
        $atime,$mtime,$ctime,$blksize,$blocks) = stat($fName);
    if ($cachedSize==$size && $cachedMtime==$mtime) {
      return ($cachedHash, $cachedSize, $cachedMtime);
    }
  }

  # we have to recompute!
  my ($dev,$ino,$mode,$nlink,$uid,$gid,$rdev,$size,
      $atime,$mtime,$ctime,$blksize,$blocks) = stat($fName);
  $cachedSize = $size;
  $cachedMtime = $mtime;
  $cachedHash = hashFileContents ($fName);
  
  # let's save the checksums
  if (open (OUTFILE, ">$fHashName")) {
    print OUTFILE 'HASH=',$cachedHash,"\n";
    print OUTFILE 'FILESIZE=',$cachedSize,"\n";
    print OUTFILE 'TIMESTAMP=',$cachedMtime,"\n";
    close OUTFILE;
  }
  
  return ($cachedHash, $cachedSize, $cachedMtime);
}


sub getDBInfoWeb {
  #in: cmd=dbinfo, db=IPI_human, release=IPI_human.22060302.fasta
  #out1: not found
  #out2: hash=????????????????????????????????
  #      filesize=???
  #      timestamp=???
  my $db = ((defined $q->param("db")) ? $q->param("db") : '');
  my $release = ((defined $q->param("release")) ? $q->param("release") : '');
  getDBInfoImpl ($db, $release);
}


sub getDBInfoImpl {
  my ($db, $release) = @_;
  $db = '' if (!defined $db);
  $release = '' if (!defined $release);

  my ($status, $statusMsg) = (0, '');
  if ($db ne '' && $release ne '') {
    ($status, $statusMsg) = getReleaseFullPath ($db, $release);
  } else {
    $status = -1;
    $statusMsg = 'Invalid parameters';
  }
  
  if (0==$status ){
    my $filename = $statusMsg;
    my ($digest, $size, $mtime) = getCachedFileChecksums($filename);
    print $q->header(-type=>'text/html');
    print 'STATUS=OK',"\n";
    print 'HASH=',$digest,"\n";
    print 'FILESIZE=',$size,"\n";
    print 'TIMESTAMP=',$mtime,"\n";
    
  } else {
    print $q->header(-type=>'text/html');
    print 'STATUS=NOT FOUND',"\n";
    print 'DETAIL='.$statusMsg,"\n";
  }
}


sub downloadDB {
  #in: cmd=downloaddb, db=IPI_human, release=IPI_human.22060302.fasta, hash=XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX, offset=?
  #out1: not found
  #out2: wrong hash?
  #out3: offset out of range
  #out4: ok
  #      <file content starting from <offset> >
  my $db = ((defined $q->param("db")) ? $q->param("db") : '');
  my $release = ((defined $q->param("release")) ? $q->param("release") : '');
  my $hash = ((defined $q->param("hash")) ? $q->param("hash") : '');
  my $offset = ((defined $q->param("offset")) ? $q->param("offset") : '');
  my $filesize = ((defined $q->param("filesize")) ? $q->param("filesize") : '');
  my $timestamp = ((defined $q->param("timestamp")) ? $q->param("timestamp") : '');
  
  my ($status, $statusMsg) = (0, '');
  if ($db ne '' && $release ne '') {
    ($status, $statusMsg) = getReleaseFullPath ($db, $release);
  } else {
    $status = -1;
    $statusMsg = 'Invalid parameters';
  }
  
  if (0==$status ){
    #TODO: do not rehash if computationally too expensive
    #      we can use the filesize and timestamp as secondary check
    my $filename = $statusMsg;
    my ($digest, $size, $mtime) = getCachedFileChecksums($filename);
    
    if ($filesize ne '') {
      $filesize = int($filesize);
      if ($size != $filesize) {
        print $q->header(-type=>'text/html');
        print 'STATUS=WRONG HASH',"\n";
        print 'DETAIL=File size does not match: ',$size,'!=',$filesize,"\n";
        return;
      }
    }

    if ($timestamp ne '') {
      $timestamp = int($timestamp);
      if ($mtime != $timestamp) {
        print $q->header(-type=>'text/html');
        print 'STATUS=WRONG HASH',"\n";
        print 'DETAIL=Timestamp does not match: ',$mtime,'!=',$timestamp,"\n";
        return;
      }
    }

    if ($offset ne '') {
      $offset = int($offset);
      if ($offset>$size) {
        print $q->header(-type=>'text/html');
        print 'STATUS=OFFSET OUT OF RANGE',"\n";
        print 'DETAIL=',$offset,'>',$size,"\n";
        return;
      }
    } else {
      $offset = 0;
    }

    if (!open(INFILE, "<$filename")) {
      $statusMsg = $!;
      print $q->header(-type=>'text/html');
      print 'STATUS=NOT FOUND',"\n";
      print 'DETAIL=Fail to open ',$filename,': ',$statusMsg,"\n";
      return;
    }
    
	  binmode(INFILE);
	  my $n;
	  my $buf;
    seek INFILE, $offset, 0; 
    # we send back a 5MB chunk each time
    $n = read(INFILE, $buf, 5*1024*1024);
    close INFILE;

    print $q->header(-type=>'text/html');
    print 'STATUS=OK',"\n";
    print 'SIZE=',$n,"\n";
    print $buf;
    
  } else {
    print $q->header(-type=>'text/html');
    print 'STATUS=NOT FOUND',"\n";
    print 'DETAIL='.$statusMsg,"\n";
  }
  
  sub getEnzymes {
	my $statusMsg = -1;
	my $filename = "../config/enzymes";
	print $q->header(-type=>'text/plain');
	if (!open(ENZYMESFILE, $filename)){
		$statusMsg = $!;
		print 'STATUS=NOT FOUND',"\n";
		print 'DETAIL=Fail to open ',$filename,': ',$statusMsg,"\n";
		return;
	}
	my @enzymes=<ENZYMESFILE>;
	if(!close(ENZYMESFILE)) {
		$statusMsg = $!;
		print 'STATUS=NOT FOUND',"\n";
		print 'DETAIL=Fail to close ',$filename,': ',$statusMsg,"\n";
		return;
	}
	my $i = 0;
	while($enzymes[$i]) {
		print $enzymes[$i],"\n";
		$i++;
	}
  }

}

