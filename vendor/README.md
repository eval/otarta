The following commands assume tracking remotes, eg:

```bash
$ git remote add -f haslett https://github.com/weavejester/haslett.git
```

## Adding vendored libs

```bash
$ git subtree add --prefix vendor/haslett haslett master --squash
```

## Updating from upstream

### what's coming?

```bash
$ git fetch haslett
$ git diff-tree -p haslett/master vendor/haslett/
```

### update

```bash
$ git subtree update --prefix vendor/haslett haslett master --squash
# provide descriptive commit message, eg: 'haslett v1.2'
```