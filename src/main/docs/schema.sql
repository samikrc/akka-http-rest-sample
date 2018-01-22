create table users
(
	email text not null primary key,
	name text,
    password text not null,
	devKey text not null unique,
    registerDate text not null
);

create table models
(
	modelKey text not null primary key,
	name text not null,
	lang text not null,
	version text not null,
	description text,
	code text not null
);

create table userSessions
(
    email text not null primary key,
    sessionToken text not null,
    startTimestamp text not null
);
