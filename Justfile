set shell := ["bash", "-uc"]

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
                --exclude result/ \
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

# run all example jepsen tests from control host (memo of options)
testall: build
    @just test zk   -s -t 10 -c 30 -f 5 -l 25 -v 10 -o 4000 -r 200
    @just test zk   -s -t 10 -c 30 -f 5 -l 25 -v 10 -o 4000 -r 200 -e
    @just test rmq  -s -t 10 -c 30 -f 5 -l 25 -v 10 -o 4000 -r 200
    @just test etcd -s -t 10 -c 30 -f 5 -l 25 -v 10 -o 4000 -r 200
    @just test etcd -s -t 10 -c 30 -f 5 -l 25 -v 10 -o 4000 -r 200 -q
    @just test etcd -s -t 10 -c 30 -f 5 -l 25 -v 10 -o 4000 -r 200 -q -g

# run the checker analysis phase only
check index *args: build
    lein run check \
        --which-test "{{index}}" \
        {{args}}

# run the checker analysis phase for all results under 'store/'
checkall *args: build
    if [[ "{{args}}" == "-r" ]]; then CHECKER="rustsop"; else CHECKER="clojure"; fi; \
    NUMRUNS=$(find store/ -maxdepth 1 -mindepth 1 -type d -not -name "latest" -not -name "current" | wc -l); \
    echo "Found $NUMRUNS runs to check... using checker: $CHECKER"; \
    mkdir -p "result/$CHECKER"; \
    for INDEX in $(seq 1 $NUMRUNS); do \
        echo; \
        echo "Checking result of run -$INDEX... checker: $CHECKER"; \
        lein run check \
            --which-test "-$INDEX" \
            {{args}} 2>&1 \
        | tee "result/$CHECKER/index-$INDEX.log"; \
    done

# launch the store exploration web server
serve:
    lein run serve
