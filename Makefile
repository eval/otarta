.PHONY: clean test compile-watch figwheel show-outdated

compile: target/app.js

compile-watch:
	clojure build.clj compile-watch

figwheel:
	clojure build.clj figwheel

test:
	clojure -Atest

test-watch:
	clojure -Atest-watch

target/app.js: 
	clojure build.clj compile

show-outdated:
	clojure -Aoutdated -a outdated

clean:
	rm -rf target cljs-test-runner-out
