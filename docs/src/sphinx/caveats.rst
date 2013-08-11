Known issues and caveats
========================

dbuild is still in development, and certain known issues or limitations are present; they will be resolved
in further upcoming releases. The main ones are listed here.

Limitations
-----------

- Each dbuild configuration file should contain one instance of Scala; although in general it may
  work, dbuild has not been tested extensively using configuration files in which an instance of
  Scala is not present.

- There is no locking. If you run more than one dbuild at the same time on the same machine, rather
  bad things may happen.

- dbuild does not cleans up the build files: the "./target" and "~/.dbuild/cache" directory will grow indefinitely; the first
  directory contains the build directories, and the second the published artifacts and other metadata.
  A cleanup tool is planned for the future, but in the meantime the size of the directories should be
  kept in check.

- dbuild-setup will modify your running sbt environment by running the equivalent of 'set' commands. These
  will show up in the session list as comments, but the session cannot be saved and restored using the
  `session save` command. In case you do, the dbuild settings will just be ignored.

|

(back to :doc:`the index <index>`)
