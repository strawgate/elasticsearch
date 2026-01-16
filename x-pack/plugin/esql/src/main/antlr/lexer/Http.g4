/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
lexer grammar Http;

//
// HTTP command - development only
//
DEV_HTTP : {this.isDevVersion()}? 'http'        -> pushMode(HTTP_MODE);

mode HTTP_MODE;
HTTP_PIPE : PIPE -> type(PIPE), popMode;

// HTTP methods
HTTP_GET : 'get';
HTTP_POST : 'post';
HTTP_PUT : 'put';
HTTP_DELETE : 'delete';

// HTTP options
HTTP_WITH : 'with';
HTTP_BODY : 'body';
HTTP_HEADERS : 'headers';
HTTP_TIMEOUT : 'timeout';
HTTP_AUTH : 'auth';

HTTP_COMMA : COMMA -> type(COMMA);
HTTP_ASSIGN : ASSIGN -> type(ASSIGN);

// URL string (quoted)
HTTP_QUOTED_STRING : QUOTED_STRING -> type(QUOTED_STRING);

HTTP_LINE_COMMENT
    : LINE_COMMENT -> channel(HIDDEN)
    ;

HTTP_MULTILINE_COMMENT
    : MULTILINE_COMMENT -> channel(HIDDEN)
    ;

HTTP_WS
    : WS -> channel(HIDDEN)
    ;
