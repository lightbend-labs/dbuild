Stability
=========

.. _section-stability:

The ``stability`` section
-------------------------

If you used spaces to build multiple versions of the same artifacts, you can also
automatically compare them, to make sure they are identical. That is done by
specifying a stability section as follows:
At the end of the build process, dbuild can automatically upload the generated artifacts to one or more
repositories. The ``deploy`` section in the build configuration file is optional; it consists of a sequence
of deploy records, with the following structure:

.. code-block:: javascript

   {
    build: {projects:..., options:...}
    
    stability: [{
     "a"    : [<project1>,<project2>,...]
     "b"    : [<project1>,<project2>,...]
    }, ... ]
   }

The specification of the lists of projects is the same previously described in :ref:`section-deploy`.
If the comparison fails, dbuild will print a report of the differences, and the final build outcome
will reflect the error condition.

|

*Next:* :doc:`repositories`.
