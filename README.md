# Jepsen Workflow Demos

Jepsen test workflow demos, with hooks to a custom Rust-implemented checker.

TODO(reference to paper)

## Systems

The demos are tested on CloudLab Ubuntu 22.04 machines. The list of currently supported systems is as follows:

- etcd-v3.1
- zookeeper-v3.4
- TODO(rabbitmq)

Make sure they are available under `/home/jepsen/<system-va.b>/` and compiled on all remote data nodes before running a test from the control node. Some systems might not need compiling & installing from source, instead just requiring a package installation.

## Usage

Fill the following hostname files:

- `hosts/host-ctrl`: the control node hostname
- `hosts/hosts-db`: one database node hostname per line

Sync repo content with all hosts:

```bash
just rsync
```

SSH connect to a node:

```bash
just sshto 0  # control node
just sshto 1  # first db node, etc.
```

Run a test workflow from the control node:

```bash
just test <system> [args ...]
```

Run only the checker analysis phase (index `-1` means latest run, etc.):

```bash
just check <index> [args ...]
```

Launch an exploration web server:

```bash
just serve
```

## References

- Jepsen framework: <https://github.com/jepsen-io/jepsen>
- Jepsen workflow tutorial: <https://github.com/jepsen-io/jepsen/blob/main/doc/tutorial/index.md>
- Knossos linearizability models: <https://github.com/jepsen-io/knossos>
- Verschlimmbesserung (etcd client): <https://github.com/aphyr/verschlimmbesserung>
- Avout (ZooKeeper-backed atoms): <https://github.com/liebke/avout>

## License

Copyright © 2025 Guanzhou Hu

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
<http://www.eclipse.org/legal/epl-2.0>.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at <https://www.gnu.org/software/classpath/license.html>.
