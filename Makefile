.PHONY: clean test compile-watch figwheel show-outdated

compile: target/app.js

compile-watch:
	clojure -A:dev build.clj compile-watch

figwheel:
	clojure -A:dev build.clj figwheel

test:
	clojure -Atest

test-watch:
	clojure -Atest-watch

target/app.js: 
	clojure -A:dev build.clj compile

show-outdated:
	clojure -Aoutdated -a outdated

clean:
	rm -rf target cljs-test-runner-out
