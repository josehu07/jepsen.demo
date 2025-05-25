//! Demonstrative checker implementation.
//!
//! TODO: currently only linearizability exploration supported, but other levels
//!       should be achievable with the same logic but confined to smaller scales
//!       due to complexity.

use std::cmp;
use std::collections::{HashMap, HashSet, VecDeque};
use std::error::Error;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::time::Instant;

use crate::types::{ClientId, Consistency, KeyType, OpData, OpSpan, Timeline, Timestamp, ValType};

/// Index into `client_queues` for a specific span.
type FeedIdx = (ClientId, usize);

/// Combined progress of each client's feeding queue.
type FeedProgress = Vec<usize>;

/// Ordering graph of operations (currently only a linear chain).
type Ordering = Vec<FeedIdx>;

/// A single possibility to be explored.
#[derive(Debug, Clone)]
struct Possibility {
    /// The ordering graph of operations (not used in uniqueness).
    /// NOTE: This is tracked to make this implementation represent the
    ///       "pureness" of the underlying algorithm; in practice, tracking
    ///       this is not necessary, and the Rust checker runs a decent
    ///       amount faster than the Clojure implementation.
    graph: Ordering,

    /// The resulting state after the operations in the graph.
    state: Option<ValType>,
    /// Maximum invoke timestamp of in-graph operations.
    max_invoke: Timestamp,

    /// The feeding progress of each client.
    feed_prog: FeedProgress,
}

impl Possibility {
    /// Create an initial possibility.
    fn initial(num_clients: usize) -> Self {
        Possibility {
            graph: vec![],
            state: None,
            max_invoke: 0,
            feed_prog: vec![0; num_clients],
        }
    }

    /// Create a new possibility.
    fn from(
        graph: Ordering,
        state: Option<ValType>,
        max_invoke: Timestamp,
        feed_prog: FeedProgress,
    ) -> Self {
        Possibility {
            graph,
            state,
            max_invoke,
            feed_prog,
        }
    }
}

impl Hash for Possibility {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.state.hash(state);
        self.feed_prog.hash(state);
    }
}

impl PartialEq for Possibility {
    fn eq(&self, other: &Self) -> bool {
        self.state == other.state && self.feed_prog == other.feed_prog
    }
}

impl Eq for Possibility {}

/// Refined type of `OpData` with only relevant info for checking.
#[derive(Debug, Clone)]
enum CkData {
    Read {
        val: Option<ValType>,
    },
    Write {
        val: ValType,
    },
    Rmw {
        rval: Option<ValType>,
        wval: Option<ValType>,
    },
}

impl CkData {
    fn from_raw(raw: OpData) -> Self {
        match raw {
            OpData::Read { val, .. } => CkData::Read { val },
            OpData::Write { val, .. } => CkData::Write { val },
            OpData::Rmw { rval, wval, .. } => CkData::Rmw { rval, wval },
        }
    }
}

/// Refined type of `OpSpan` with only relevant info for checking.
#[derive(Debug, Clone)]
struct CkSpan {
    invoke: Timestamp,
    finish: Timestamp,
    data: CkData,
}

impl CkSpan {
    fn from_raw(raw: OpSpan) -> Self {
        CkSpan {
            invoke: raw.invoke,
            finish: raw.finish,
            data: CkData::from_raw(raw.data),
        }
    }

    fn terminated(&self) -> bool {
        self.finish != 0
    }
}

impl fmt::Display for CkSpan {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "|{}-{} {}|",
            self.invoke,
            self.finish,
            match self.data {
                CkData::Read { val } => format!(
                    "R({})",
                    if let Some(val) = val {
                        val.to_string()
                    } else {
                        "nil".to_string()
                    }
                ),
                CkData::Write { val } => format!("W({})", val),
                CkData::Rmw { rval, wval } => format!(
                    "CAS({},{})",
                    if let Some(rval) = rval {
                        rval.to_string()
                    } else {
                        "nil".to_string()
                    },
                    if let Some(wval) = wval {
                        wval.to_string()
                    } else {
                        "nil".to_string()
                    }
                ),
            },
        )
    }
}

/// Overall checker, a collection of per-key checkers.
#[derive(Debug)]
pub(crate) struct Checker {
    per_key: HashMap<KeyType, CheckerPerKey>,
}

impl Checker {
    /// Create a new checker. Split the timeline into per-key stream groups,
    /// and check each stream independently.
    pub(crate) fn new(timeline: Timeline) -> Self {
        let mut per_key_spans = HashMap::new();
        for (&key, &cnt) in timeline.stats_key_ops.iter() {
            per_key_spans.insert(key, vec![Vec::with_capacity(cnt); timeline.num_clients()]);
        }
        for (client, queue) in timeline.queues.into_iter().enumerate() {
            for span in queue {
                if let Some(spans) = per_key_spans.get_mut(&span.key()) {
                    spans[client].push(CkSpan::from_raw(span));
                }
            }
        }

        let mut per_key_checkers = HashMap::new();
        for (key, spans) in per_key_spans.into_iter() {
            per_key_checkers.insert(key, CheckerPerKey::new(spans));
        }

        Checker {
            per_key: per_key_checkers,
        }
    }

    /// Run the check for all keys.
    pub(crate) fn check(&mut self) -> Result<Consistency, Box<dyn Error>> {
        let mut result = Consistency::Linearizable;

        // TODO: should be super easy to parallelize here at this loop, but
        //       there are probably a million ways to further optimize
        for (key, checker) in self.per_key.iter_mut() {
            println!(" checking key {} ...", key);
            let level = checker.check()?;

            if level < result {
                result = level; // take minimum level strength across keys
            }
            if level == Consistency::Weak {
                break;
            }
        }

        Ok(result)
    }
}

/// Checker states per key.
#[derive(Debug)]
struct CheckerPerKey {
    /// Spans from the timeline, one stream per client.
    client_queues: Vec<Vec<CkSpan>>,

    /// Queue of current possibilities. Each possibility is a tuple of the
    /// (ordering graphs, resulting state, feeding progress) at that point.
    possibilities: VecDeque<Possibility>,

    /// Set of unique possibilities for uniqueness comparison.
    possibilities_set: HashSet<Possibility>,
}

impl CheckerPerKey {
    /// Create a new per-key checker.
    fn new(client_queues: Vec<Vec<CkSpan>>) -> Self {
        let num_clients = client_queues.len();
        let initial = Possibility::initial(num_clients);

        CheckerPerKey {
            client_queues,
            possibilities: VecDeque::from([initial.clone()]),
            possibilities_set: HashSet::from([initial]),
        }
    }

    /// Check the history.
    fn check(&mut self) -> Result<Consistency, Box<dyn Error>> {
        let mut last_print = Instant::now();

        while let Some(possib) = self.possibilities.pop_front() {
            // only for auxiliary printing ...
            let now = Instant::now();
            if now.duration_since(last_print).as_millis() > 500 {
                last_print = now;
                println!(
                    "  ...  ∑ feed_prog: {:5}  |possib|: {:8}  |unique|: {:8}",
                    possib.feed_prog.iter().sum::<usize>(),
                    self.possibilities.len(),
                    self.possibilities.iter().collect::<HashSet<_>>().len(),
                );
            }
            // ... auxiliary printing ends

            let mut client_end_count = 0;
            for (client, idx) in possib.feed_prog.clone().into_iter().enumerate() {
                if idx == self.client_queues[client].len() {
                    client_end_count += 1;
                    continue;
                }

                let feeding = &self.client_queues[client][idx];
                if !feeding.terminated() {
                    continue;
                }

                Self::handle_feed_attempt(
                    &possib,
                    feeding,
                    (client, idx),
                    &self.client_queues,
                    &mut self.possibilities,
                    &mut self.possibilities_set,
                );
            }

            if client_end_count == self.client_queues.len() {
                // found a possible ordering where all spans fit in the ordering
                return Ok(Consistency::Linearizable);
            }
        }

        Ok(Consistency::Weak)
    }

    /// Process a feeding attempt, producing zero or more new possibilities.
    fn handle_feed_attempt(
        possib: &Possibility,
        feeding: &CkSpan,
        feeding_idx: FeedIdx,
        _client_queues: &Vec<Vec<CkSpan>>,
        possibilities: &mut VecDeque<Possibility>,
        possibilities_set: &mut HashSet<Possibility>,
    ) {
        // print!("  ");
        // for &(client, idx) in &possib.graph {
        //     let span = &_client_queues[client][idx];
        //     print!(" {}", span);
        // }
        // println!(
        //     " ~{} @{} <- {}",
        //     possib.max_invoke,
        //     if let Some(state) = possib.state {
        //         state.to_string()
        //     } else {
        //         "-".to_string()
        //     },
        //     feeding
        // );

        // check on timestamp span first
        if feeding.finish < possib.max_invoke {
            return;
        }

        // check if this operation can be appended to the current graph with
        // matching state
        if let Some(new_possib) = Self::try_append_new_span(possib, feeding, feeding_idx) {
            if !possibilities_set.contains(&new_possib) {
                possibilities.push_back(new_possib.clone());
                possibilities_set.insert(new_possib);
            }
        }
    }

    /// Try to append the operation to the end of the graph, returning
    /// `Some(new_possibility)` if success.
    fn try_append_new_span(
        possib: &Possibility,
        feeding: &CkSpan,
        feeding_idx: FeedIdx,
    ) -> Option<Possibility> {
        if let Some((new_graph, new_state)) = match &feeding.data {
            CkData::Read { val } => {
                if &possib.state == val {
                    Some((possib.graph.clone(), possib.state.clone()))
                } else {
                    None
                }
            }

            CkData::Write { val } => {
                let mut new_graph = possib.graph.clone();
                new_graph.push(feeding_idx);
                Some((new_graph, Some(*val)))
            }

            CkData::Rmw { rval, wval } => {
                if &possib.state == rval {
                    let mut new_graph = possib.graph.clone();
                    new_graph.push(feeding_idx);
                    Some((new_graph, wval.clone()))
                } else {
                    None
                }
            }
        } {
            // state matches, compose the new possibility with the next feeding
            // progress vector where this client's index is incremented
            let client = feeding_idx.0;
            let mut new_feed_prog = possib.feed_prog.clone();
            new_feed_prog[client] += 1;

            Some(Possibility::from(
                new_graph,
                new_state,
                cmp::max(feeding.invoke, possib.max_invoke),
                new_feed_prog,
            ))
        } else {
            None
        }
    }
}
