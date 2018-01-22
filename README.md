# akka-http-rest-sample

## Introduction

This is a sample codebase for a set of REST api and user management using akka-http. The code provides the backend (and the front end - HTML files are also included) for a small app for editing some model code, which is behind a login page. The code covers a few things which needed some research:

* Sending session tokens and devKey for accessing API through cookies
* The devKey is also sent through header (presumably for using through curl-type clients)

On the code side, shows some example of:

* How to combine multiple akka-http directives using &
* How to construct new directives by combining existing directives
* How to write semi-custom unmarshallers - there is a JSON unmarsheller using a lightweight, single file JSON parser/writer

## Instructions

The code uses a SQLite database at the backend, the schema for which is also in the codebase (src/main/docs). Steps for setting up the backend:
1. Initialize the database in a folder (e.g., ~/db): sqlite3 -init ~/git/akka-http-rest-sample/src/main/docs/schema.sql sample.db
2. Edit the application.conf (in src/main/resources) file to point to this database

The rest of the stack can be initialized as follows:
1. Set up an Apache web server and point the /var/www/html to src/main/html through symlink (or whatever way you would prefer)
2. Run RESTWebServer.scala in src/main/scala/com/ilabs/dsi/restapi
3. Run UMWebServer.scala in src/main/scala/com/ilabs/dsi/usermgmt/
4. Go to http://localhost/login.html
