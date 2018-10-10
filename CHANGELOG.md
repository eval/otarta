## 0.3.1 / 2018-10-10

### Added

* log connection loss

### Fixed

* otarta.main: timeout-channels won't prevent node process from exiting

## 0.3.0 / 2018-10-02

### Breaking

* Formats: `PayloadFormat` moved to `otarta.format` (was `otarta.payload-format`)
* Formats: Implement `-read` and `-write` (was `read` and `write`)
* msg from subscription has :retain? instead of :retained?

### Added

* documentation
* `otarta.core/disconnect` is idempotent
* publish accepts retain?-option
* client accepts `client-id-prefix`: for unique, but recognizable client-id
* send first PINGREQ only after keep-alive seconds

### Fixed

* generated client-id MUST be accepted as per spec
