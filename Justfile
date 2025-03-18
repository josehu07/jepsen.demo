# list just recipes
default:
    @just --list --unsorted

# default identifiers
reponame := "jepsen.demo"
username := "jepsen"
password := "jepsen"
nodesfile := "nodehosts"
prkeyfile := "/etc/ssh/" + username + "/cluster_id_rsa"

# sync repo to all remotes
rsync:
    while IFS="" read -r hostname || [ -n "$hostname" ]; do \
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
            . "{{username}}@${hostname}:~/{{reponame}}"; \
    done < nodehosts

# ssh login to given host index
sshto nid="0":
    ssh -o "StrictHostKeyChecking=no" \
        "{{username}}@$(sed -n "$(( {{nid}} + 1 ))p" nodehosts)"

# run a jepsen test (WIP)
test *args:
    lein run test \
        --nodes-file "{{nodesfile}}" \
        --username "{{username}}" \
        --password "{{password}}" \
        --ssh-private-key "{{prkeyfile}}" \
        {{args}}

# launch the exploration web server
serve:
    lein run serve
