# -*- coding: utf-8 -*-

import sys, os

# so we can do the relative import, as per
# https://stackoverflow.com/a/73932641/86485
sys.path.append(__file__.rsplit("/", 1)[0])

from version import release

sys.path.append(os.path.abspath('_sphinx/exts'))
extensions = ['sphinx.ext.extlinks', 'howto']

# Project variables

project = 'dbuild'

# General settings

needs_sphinx = '1.1'
nitpicky = True
default_role = 'literal'
master_doc = 'index'
highlight_language = 'scala'
add_function_parentheses = False

# HTML

html_theme = 'dbuild'
html_theme_path = ['_sphinx/themes']
html_title = 'dbuild Documentation'
html_domain_indices = False
html_use_index = False
html_show_sphinx = False
htmlhelp_basename = 'dbuilddoc'
html_use_smartypants = False
html_copy_source = False

# if true:
#  the Home link is to scala-sbt.org
# if false:
#  the Home link is to home.html for the current documentation version
# TODO: pass this as an argument to sphinx

#home_site = True
home_site = False

# Passed to Google as site:<site_search_base>
# If empty, no search box is included
# TODO: pass this as an argument to sphinx, use actual version instead of release 

#site_search_base = 'https://www.scala-sbt.org/release/docs'
site_search_base = ''

# passes variables to the template
html_context = {'home_site': home_site, 'site_search_base': site_search_base}

rst_epilog = """
.. role:: nv
.. role:: s2
.. _zip: %(dbuild_native_package_base)s/%(project)s/%(version)s/zips/%(project)s-%(version)s.zip
.. _tgz: %(dbuild_native_package_base)s/%(project)s/%(version)s/tgzs/%(project)s-%(version)s.tgz
.. |version| replace:: %(version)s
.. |addSbtplugin| replace:: addSbtPlugin(:s2:`"com.typesafe.dbuild"` %% :s2:`"plugin"` %% :s2:`"%(version)s"`)
""" % {
   'version': release,
   'project': project,
   'dbuild_native_package_base': 'https://repo.typesafe.com/typesafe/ivy-releases/com.typesafe.dbuild',
}
