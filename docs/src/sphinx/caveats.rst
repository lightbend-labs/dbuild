Known issues and caveats
========================

dbuild is still in development, and certain known issues or limitations are present; they will be resolved
in further upcoming releases. The main ones are listed here.

Limitations
-----------

- There is no locking. If you run more than one dbuild at the same time on the same machine, rather
  bad things may happen.

- dbuild does not cleans up the build files: the "./target" and "~/.dbuild/cache" directory will grow indefinitely; the first
  directory contains the build directories, and the second the published artifacts and other metadata.
  A cleanup tool may be added in the future, but in the meantime the size of the directories should be
  kept in check.

- dbuild-setup will modify your running sbt environment by running the equivalent of 'set' commands. These
  will show up in the session list as comments, but the session cannot be saved and restored using the
  `session save` command. In case you do, the dbuild settings will just be ignored.

- dbuild-setup can get confused if the directory name contaning the code is the same as the name of one
  of the projects. If that should happen, just rename the directory or clone the code using a different
  directory name, as in:

.. code-block:: text

  $ git clone git://github.com/etorreborre/specs2.git specs-dir

|

(back to :doc:`the index <index>`)
