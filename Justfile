# list just recipes
default:
    @just --list --unsorted

# default identifiers
reponame := "jepsen.demo"
username := "jepsen"
password := "jepsen"
ctrlhostfile := "hosts/host-ctrl"
dbhostsfile := "hosts/hosts-db"
prkeyfile := "/etc/ssh/" + username + "/cluster_id_rsa"

# sync repo to all remotes
rsync:
    for HOSTFILE in {{ctrlhostfile}} {{dbhostsfile}}; do \
        while IFS="" read -r HOSTNAME || [ -n "$HOSTNAME" ]; do \
            rsync -aP --delete \
                --exclude .git/ \
                --exclude .clj-kondo/ \
                --exclude .venv/ \
                --exclude .DS_Store \
                --exclude .lsp/ \
                --exclude .vscode/ \
                --exclude __pycache__/ \
                --exclude .lein-repl-history \
                --exclude debug/ \
                --exclude target/ \
                --exclude store/ \
                . "{{username}}@${HOSTNAME}:~/{{reponame}}"; \
        done < ${HOSTFILE}; \
    done

# ssh login to given host ("0" for control host)
sshto nid="0":
    if [ "{{nid}}" -eq "0" ]; then \
        ssh -o "StrictHostKeyChecking=no" \
            "{{username}}@$(sed -n "1p" {{ctrlhostfile}})"; \
    else \
        ssh -o "StrictHostKeyChecking=no" \
            "{{username}}@$(sed -n "$(( {{nid}} ))p" {{dbhostsfile}})"; \
    fi

# build everything
build:
    lein compile
    cargo build -r

# run a jepsen test from control host
test system *args: build
    lein run {{system}} test \
        --nodes-file "{{dbhostsfile}}" \
        --username "{{username}}" \
        --password "{{password}}" \
        --ssh-private-key "{{prkeyfile}}" \
        {{args}}

# run the checker analysis phase only
check index *args: build
    lein run check \
        --which-test "{{index}}" \
        {{args}}

# launch the store exploration web server
serve:
    lein run serve
