# Otarta

An MQTT-client written in ClojureScript.


## CLI

The CLI allows you to subscribe from the commandline.  

### start local broker

(Skip this step if you already have a broker with websocket access.)

```bash
$ docker run --rm -ti -p 9001:9001 toke/mosquitto
```

### pub&sub

```bash
# one time setup
$ make compile

# subscribe to broker's SYS-topics
$ clj -m cljs.main -re node -m otarta.main sub ws://localhost:9001 '$SYS/#' -d

# publish some message
$ clj -m cljs.main -re node -m otarta.main pub ws://localhost:9001 'foo/bar' 'baz' -d

# to disable logging, remove `-d`
```

## development

### testing

```bash
# once
$ clojure -Atest

# watching
$ clojure -Atest-watch
```

### Figwheel

```bash
# start figwheel
$ make figwheel

# wait till compiled and then from other shell:
$ node target/app.js

# then from emacs:
# M-x cider-connect with host: localhost and port: 7890
# from repl:
user> (figwheel/cljs-repl)
;; prompt changes to:
cljs.user>
```

See [CIDER docs](https://cider.readthedocs.io/en/latest/interactive_programming/) what you can do.


## Release

### Install locally

- bump version in pom.xml
- mvn clean install
- testdrive locally

### Deploy to Clojars

- commit pom.xml to master
- push to CI


## License

Copyright (c) 2018 Alliander N.V. See [LICENSE](./LICENSE).

For licenses of third-party software that this software uses, see [LICENSE-3RD-PARTY](./LICENSE-3RD-PARTY).
