Known issues and caveats
========================

dbuild is still in development, and certain known issues or limitations are present; they will be resolved
in further upcoming releases. The main ones are listed here.

Limitations
-----------

- There is no locking. If you run more than one dbuild instance at the same time on the same machine,
  rather bad things may happen. Running two dbuild instances under different accounts is safe, as long
  as the builds take place in different directories.

- dbuild does not cleans up all the generated files: the "~/.dbuild/cache-{ver}" directory, containing
  the generated artifacts and some metadata, will grow indefinitely. Usually the size is rather modest,
  but you may want to keep it in check. The much larger directories containing the actual build files,
  located in "./target-{ver}", are instead cleaned automatically.

|

(back to :doc:`the index <index>`)
