Notifications
=============

.. _section-notifications:

The ``notifications`` section
-----------------------------

Another task that dbuild can automatically take care of, after the build, is notifying users
and maintainers about the outcomes of the various compilations, using of a rather
flexible notification system.

Notifications are controlled using the following description:

.. code-block:: text

   {
    build: {projects:..., options:...}

    notifications: {
     send      : [<notification1>,<notification2>,...]
     default   : [<recipient-pattern1>,<recipient-pattern2>,...]
     templates : [<optional-template1>,
    }
   }

send
  Is a list of notifications. Each notification specifies when and where to send
  a message, the kind of the message, and the kind of report that should be sent.
  The text of each notification is built using message templates, described below.

default
  Multiple notifications may use common information; for example,
  in the case of email messages, many notifications may use the same mail server,
  or may all have a common "CC" recipient. The "default" list allows you to specify just once
  some defaults that will be inherited by all the notifications, which
  will then be able to override them individually. See below for a useful example.
  The list of defaults is optional.

template
  By default, the text of messages is generated using some standard templates,
  depending on the kind of message. As an additional facility it is possible to
  define custom templates in order to personalize the message for one or multiple
  notification.
  The templating facility is still preliminary and some details may change in future
  versions. In normal usage, this section can just be omitted in order to use the
  standard templates.

The "send" and "default" fields, in addition to a list, will also accept a single
notification; in that case, the square brackets can be omitted. That,
combined with the extended JSON syntax of the
`config library <http://github.com/typesafehub/config>`_, makes for a practical
shortened syntax. An example can be found later on this page.

If the list of notifications is empty, by default a short report will be printed to
the console.

If you are working on a build configuration file that is normally deployed elsewhere,
for instance on a remote Jenkins server, and you want to test it locally without
having notifications sent out, just append the flag ``--no-notify`` (or ``-n``) to
your command line. Use ``dbuild --help`` to find out further details and options.


Notifications
-------------

Each notification has the following structure:

.. code-block:: javascript

     {
      kind     : <notification-kind>,
      when     : <when-selector>,
      send     : <message-recipients>,
      projects : [<project1>,<project2>,...]
      template : <optional-template-selector>
     }

kind
  The "kind" of a notification refers to the mechanism that is used to deliver the
  message. The notification infrastructure has been designed to be very easily
  extendable with further notification systems, like tweets, posts, etc. The
  notification kinds available at this time are "email", "flowdock", and "console".
  If "kind" is not specified, it will be assumed to be "email".

when
  There are several possible outcomes from a dbuild run. For example, it could be
  that the dependency
  extraction phase was unsuccessful, or the build of one or more projects failed.
  Maybe deployment failed, or rather everything was ok. The "when" selector allows
  you to select exactly when the notification should be sent out. It can be a
  single identifier, or a list of identifiers. The list will be matched against the
  outcome of a project, and the message will be sent out only if there is a match.
  The list of possible outcomes is described below.

send
  This is a kind-specific description of the intended recipients of the notification.
  The exact details depend on the notification kind, and are listed further down
  on this page.
 
projects
  This is a list of projects to which the notification applies. Within the same
  notification, a separate message will be sent for each of the listed projects.
  If you use the string ".", a message will be sent for the outcome of the entire
  dbuild run. Each report will be especially
  tailored for that project, with its specific dependencies, etc. However, each
  report will be sent only if the outcome of that project matches the "when"
  selector of the notification.

template
  The optional string "template" can be used to select one of the custom or standard
  templates. There are three predefined templates: "email", "flowdock", and "console". If the template
  is not specified, the template with the same name as the kind will be used. However,
  you could in theory send an email using the console template, or print onscreen the
  text of an email. If you want to use a custom template, just define it in the
  "templates" section, and refer to it here by using its identifier.
  You can also override the standard template for all the
  email messages by defining in the templates section a template called "email",
  which will override the standard one.


Outcomes and "when"
-------------------

A notification will be processed for a project only when the outcome of the project
matches the "when" selector. The possible outcomes are arranged in a tree, as follows:

.. code-block:: text

   always
   |
   +----- good +---- success
   |           |
   |           +---- unchanged
   |
   +------ bad +---- failed
               |
               +---- dep-broken
               |
               |
               +---- extraction +---- extraction-ok
               |                |
               |                +---- extraction-failed
               |
               +---- task-failed
               |
               |
               +---- unexpected


The result for each project, as well as for dbuild as a whole, will always be one
of these outcomes. Since outcomes are hierarchical, each of them will be selected
when the parent is selected. For example, let's assume that the "when" clause is
``"bad"``. The notification will be sent if it is "bad", or "failed", or "dep-broken",
or any other bad condition. If the "when" clause is ``["bad","success"]``, then a
message is sent upon a build's first success, or when something bad happened.

The outcomes are the following:

always
  Not a real possible outcome; use "always" when you want a notification to be
  generated at each run, regardless of the result.

good
  The "good" outcome groups all of the successful results of a dbuild or project run.

bad
  Something went wrong, either when extracting dependencies, or while building,
  or while running some accessory task.

success
  This outcome means that the project or the whole dbuild had a successful test run.
  For a project, it means that there was some change in either its own source
  code, or that there was a change in one of its dependencies. Therefore, the project
  had to be recompiled, and the compilation completed successfully. For dbuild as a
  whole (project "."), it means that there was a good run and nothing failed.

unchanged
  It means that a project was not rebuilt, since nothing changed in its code
  or in any of its dependencies: its cached artifacts were used. The main
  dbuild (project ".") is always executed, therefore its outcome cannot
  be "unchanged": if all its projects are unchanged and all the accessory tasks
  completed successfully, its final outcome will be "success".

failed
  This outcome means that dbuild reached the point in which the actual project
  compilation started, but some error occurred while compiling.

dep-broken
  It may happen that the first stage, project dependencies extraction, completed
  successfully for all projects. However, when we reached the compilation stage,
  one of the projects that this project depends on failed to build. As a result,
  its dependent projects cannot be built: the status "dep-broken" means that
  we cannot build this project until some other project has been fixed.

extraction
  It means that we were unable to proceed to the building
  stage, and stopped right after dependency extraction; it is used to group
  extraction-ok and extraction-failed.

extraction-ok
  This outcome is generated if dependency extraction for this particular project
  succeeded (but we could not proceed to further stages).

extraction-failed
  It means that this project failed during the very first stage, while inspecting
  the project in order to find which other projects it depends on. It could be
  that the project build file is broken, or that we were unable to check out its
  source code.

task-failed
  This is a "combo" status, in the sense that it also encapsulates a further
  outcome. This error is generated when one of the accessory tasks of dbuild
  somehow failed to run. For instance, it may mean that we could not deploy
  the generated artifacts to a repository, or that we could not sign them, or
  somethings similar. Since it is generated after dbuild has already accumulated
  a build/extraction outcome, the initial outcome is preserved inside it;
  the corresponding diagnostic message will print both.

unexpected
  As a very special case, "unexpected" could possibly be generated in extreme
  and exceptional circumstances, for instance as a result of an internal error.
  During normal
  operation, all of the possible error conditions and exceptions that are
  generated while building, deploying, etc, will lead instead to one of the other
  outcomes. Being only a truly unexpected occurrence, the generation of this
  outcome bypasses the notifications system, and cannot be captured.

If the "when" clause is omitted, the default is ``["bad","success"]``, meaning that
a message is sent when something is wrong, or upon the first successful recompilation
of a project, when changes occur.

.. Note::

   Technically, the notifications stage run as a post-build task. However, it
   cannot report about errors that happen during notification itself.
   The outcomes that the
   notifications task will observe, therefore, is the one available after building, and after
   running all the other tasks; notifications come last. Should anything go
   wrong during notification, a diagnostic message will just be printed onscreen,
   and an error will be returned (which can be captured by Jenkins, for example).
   A further, last-resort error handler may be added in the future.

The "send" clause
-----------------

Each notification kind may need further information concerning exactly where to
address the resulting messages. For the predefined kind "console" at this time
there is no further information needed. For the kind "email", the "send"
clause is the following:

.. code-block:: text

     {
      to     : [<addr1>,<addr2>,...]
      cc     : [<addr1>,<addr2>,...]
      bcc    : [<addr1>,<addr2>,...]
      from   : <addr>
      smtp   : <server-parameters>
     }

to, cc, bcc
  They can be either a single string, or a list of strings, each specifying an email
  address in the usual format. They can be in the format ``user@host``, or in the format
  ``Name <user@host>``, according to the RFC 822 specification.

from
  The sender that will appear in the messages. If not specified, dbuild will assemble
  an email address using the current user name and the host name of the current machine.

smtp
  A specification of the email server to which the messages will be sent to (see below).
  If missing, dbuild will try to contact the smtp server running on localhost, port 25,
  no encryption. The smtp record is:

.. code-block:: text

     {
      server            : <host>
      encryption        : <auth-mechanism>
      credentials       : <filename>
      check-certificate : <true-or-false>
     }

server
  It is the SMTP server used to relay messages. If missing, it is assumed to be localhost.

encryption
  The encryption mechanism. It can be: "none" (port 25, no encryption), "ssl" (port 465,
  encryption required), "starttls" (port 25, encryption required), or "submission" (port 587,
  encryption required). Please note that encryption is unrelated to authentication: you can
  have an SSL-encrypted session on port 465 also with a server that does not require
  authentication.

credentials
  If authentication is required, you can specify here the pathname of a properties file, which
  should contain at least the properties "host", "user", and "password". The value of the "host"
  property must match the smtp server name. The "user" property is the name used during
  authentication; it can be "name", or "name@somehost", depending on the providers.

check-certificate
  When connecting using encryption, the validity of the SSL certificates is usually verified,
  and the connection denied if verification fails. However, in case of self signed or test
  certificates, it may be necessary to skip the certificate validation. The field
  check-certificate is by default true, but you can explicitly set it to "false" in order to
  bypass SSL certificate verification.

Flowdock
--------
For Flowdock notifications, the "send" clause is:

.. code-block:: text

     {
      token     : <api-token>
      detail    : <summary-or-short-or-long>
      sender    : <sender-name>
      tags      : <optional-tags>
     }

token
  This is the Flowdock API token for the desired flow (it can be obtained from the
  Flowdock interface, clicking on the settings gear)

detail
  Optional, it can be one of "summary", "short" (default), or "long". It selects
  the detail level of the notification text, with summary being a one-line message,
  and long being a full report of all subprojects. For instance, in order to reduce
  visual clutter, different notifications can be used together with different detail
  levels: "when: bad, detail: long" and "when: good, detail: summary".

sender
  The name that Flowdock will display within the flow as the message sender. It need
  not match any existing user in the system.

tags
  An optional list of tags, which will be used by Flowdock to categorize the message.

Example
-------

Using the extended JSON syntax supported by the Typesafe config library, and the list
of defaults, the notifications can be expressed in a compact manner. For example, consider
the following example:

.. code-block:: text

   build.projects:[{...}]
   options.notifications.send:[{
       projects: aabb
       send.to: "user1@typesafe.com"
      },{
       projects: [ccdd,eeff]
       send.to: "user2@typesafe.com"
      },{
       projects: "."
       send.to: "user3@typesafe.com"
       when: [good, task-failed]
      },{
       projects: "."
       kind: console
       when: always
   }]
   options.notifications.default.send:{
    from: "your dbuild <dbuild@server.com>"
    smtp:{
     server: "smtp.server.com"
     encryption:  "ssl"
     credentials: "/home/user/.credentials-server"
   }}

The meaning is fairly obvious: a report about project aabb
is sent by email to user1 each time the build fails or succeeds for the first time (the default
is ["bad","success"]). The same applies for projects ccdd and eeff and user2. A report is sent to user3
with a report for the entire dbuild run in certain cases, and a short report is always printed
to the console. All of the email notifications, by default, will use the settings specified in
the default section, unless overridden.

This example uses a number of shortcuts. To begin with, the dot-notation is used to simplify
the structure of the configuration file: ``build.projects`` is equivalent to ``build:{projects:{``.
The double quotes have been omitted from most strings and labels, as well as commas. Then,
single strings have been used where a list was expected. Also, in the default list, we wrote
``options.notifications.default.wrote``. That is equivalent to having a single notification, used
in place of a list for the defaults, in which the default kind is "email" and in which
we specify the email default arguments for all the other email notifications. The defaults
section is therefore equivalent to the somewhat more verbose:

.. code-block:: javascript

    "options":{
      "notifications":{
        "default":[{
           "kind"  : "email",
           "send" :{
             "from": "your dbuild <dbuild@server.com>",
             "smtp":{
               "server": "smtp.server.com",
               "auth"  : "ssl",
               "credentials": "/home/user/.credentials-server"
             }
           }
         }
        }]
        "send": ...


Templates
---------

In order to customize the way in which reports are generated, it is
possible to create custom report templates, which are then used by
specifying their name in the notifications.
It is also possible to redefine the standard
templates "console" and "email", which will then be used for all of
the corresponding reports.

A template is defined as:

.. code-block:: text

   options.notifications.templates: [{
     id      : <template-name>
     summary : <summary-string>
     short   : <short-string>
     long    : <long-string>
   },...]

id
  The id of the template; it is then referred to from the field "template"
  of the notifications.

summary
  A summary should be <50 characters, with a short message informing of what went wrong.
  It is a required field in the template, and should be suitable, for example,
  for a short console report or as an email subject line.

short
  A slightly longer short summary (<110 characters), suitable for SMS, Tweets, etc.
  It should be self-contained in terms of information. Defaults to the short summary.

long
  A long body with a more complete description. Defaults to the short message. Do not
  terminate any of the three descriptions with a ``\n``. A newline will be added by the
  notification system only if it is required in that specific case.

Once a notification is ready to send a message, and the project outcome is available, the
final message will be created by using the template, the outcome, the environment variables,
and some template variables prepared by dbuild.

The environment variables can be substituted into the template using ``${VARIABLE}``.
All of the Jenkins variables are also available, and
can be used to build informative messages. The dbuild-specific
variables (properties) are the following (this list is subject to adjustments
and changes):

.. code-block:: text

   ${dbuild.template-vars.project-name}
   ${dbuild.template-vars.status}
   ${dbuild.template-vars.subprojects-report}
   ${dbuild.template-vars.project-description}
   ${dbuild.template-vars.padded-project-description}
   ${dbuild.template-vars.config-name}

${dbuild.template-vars.project-name}
  The name of the project we are sending a report about, or "." for the root build.

${dbuild.template-vars.status}
  A short status string from the outcome. It can be, for instance:
  ``EXTRACTION FAILED (Exception: Couldn't resolve)``.

${dbuild.template-vars.subprojects-report}
  A compact report of the name and status of all of the projects that are
  our dependencies; useful to determine the cause a broken dependencies status.
  The variant "subprojects-report-tabs" prepends each line with a tab
  character (used in Flowdock notifications).

${dbuild.template-vars.project-description}
  The name of the project, preformatted as eithed "project <name>", or "dbuild" for the root.

${dbuild.template-vars.padded-project-description}
  As above, but padded to the left with "-" characters to a predetermined length.

${dbuild.template-vars.config-name}
  The name of the configuration file that was passed as a parameter to dbuild.

In addition, as mentioned, if dbuild runs under Jenkins its environment variables are also
available; for example ``${BUILD_URL}``, ``${JOB_NAME}``, and ``${NODE_NAME}``.

For example, the long format of the standard "email" template is:

.. code-block:: text

   This is a test report for ${dbuild.template-vars.project-description} in the dbuild configuration "${dbuild.template-vars.config-name}"
   running under the Jenkins job "${JOB_NAME}" on ${NODE_NAME}.
   
   ${dbuild.template-vars.subprojects-report}
   ** The current status of ${dbuild.template-vars.project-description} is:
   ${dbuild.template-vars.status}
   
   
   A more detailed report of this dbuild run is available at:
   ${BUILD_URL}console

|

*Next:* :doc:`comparison`.

