# list just recipes
default:
    @just --list --unsorted

# default identifiers
username := "josehu"
reponame := "jepsen.demo"

# sync repo to all remotes
rsync:
    rsync -aP --delete \
        --exclude .git/ \
        --exclude .venv/ \
        --exclude .DS_Store \
        --exclude .vscode/ \
        --exclude "sumgen/__pycache__/" \
        --exclude "debug/" \
        --exclude "target/" \
        --exclude "ycsb/" \
        --exclude "backer.*/" \
        --exclude "report/" \
        . "{{username}}:{{hostname}}:~/{{reponame}}"
