//! Definition of fundamental types for consistency checking.

use std::collections::HashMap;
use std::error::Error;
use std::fmt;

/// Client ID type.
pub(crate) type ClientId = usize;

/// Key type.
pub(crate) type KeyType = u64;

/// Value type.
pub(crate) type ValType = u64;

/// Timestamp type.
pub(crate) type Timestamp = u64;

// Operation-unique tag type.
pub(crate) type UniqueTag = u64;

/// Event type enum.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EventType {
    Invoke, // :invoke
    Okay,   // :ok
    Fail,   // :fail
    Error,  // :info (indicating error)
}

/// Operation type and data. Option fields are `None` when the operation is
/// on the fly and result values are not known yet. A value could also remain
/// `None` after the timeline has parsed due to failed read.
#[derive(Debug, Clone)]
pub(crate) enum OpData {
    Read {
        key: KeyType,
        val: Option<ValType>,
        tag: Option<UniqueTag>,
    },
    Write {
        key: KeyType,
        val: ValType,
        tag: UniqueTag,
    },
    /// Any general operation fits RMW (read-modify-write). A common example
    /// beyond regular reads/writes is CAS (compare-and-swap).
    /// In our current implementation, only :cas is supported for this type.
    /// The function from `rval` to `wval` is assumed correct.
    Rmw {
        key: KeyType,
        rval: Option<ValType>,
        rtag: Option<UniqueTag>,
        wval: Option<ValType>,
        wtag: Option<UniqueTag>,
    },
}

impl fmt::Display for OpData {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            OpData::Read { key, val, .. } => {
                write!(
                    f,
                    "R_{}:{}",
                    key,
                    val.as_ref().map(|v| v.to_string()).unwrap_or("-".into())
                )
            }
            OpData::Write { key, val, .. } => write!(f, "W_{}<{}", key, val),
            OpData::Rmw {
                key, rval, wval, ..
            } => {
                write!(
                    f,
                    "RMW_{}:{}<{}",
                    key,
                    rval.as_ref().map(|v| v.to_string()).unwrap_or("-".into()),
                    wval.as_ref().map(|v| v.to_string()).unwrap_or("-".into())
                )
            }
        }
    }
}

impl OpData {
    /// Check if I form a matching pair with a previous `OpData`.
    fn match_previous(&self, prev: &OpData) -> bool {
        match (self, prev) {
            (OpData::Read { key, .. }, OpData::Read { key: k, .. }) => key == k,
            (OpData::Write { key, val, .. }, OpData::Write { key: k, val: v, .. }) => {
                key == k && val == v
            }
            (OpData::Rmw { key, .. }, OpData::Rmw { key: k, .. }) => key == k,
            _ => false,
        }
    }

    /// Overwrite my value fields with the other `OpData` values.
    fn overwrite_by(&mut self, other: OpData) {
        match (self, other) {
            (OpData::Read { val, .. }, OpData::Read { val: v, .. }) => *val = v,
            (
                OpData::Rmw { rval, wval, .. },
                OpData::Rmw {
                    rval: rv, wval: wv, ..
                },
            ) => {
                *rval = rv;
                *wval = wv;
            }
            _ => {}
        }
    }
}

/// Operation span type, i.e., operation data with invocation and completion
/// timestamps.
#[derive(Debug, Clone)]
pub(crate) struct OpSpan {
    pub(crate) invoke: Timestamp,
    pub(crate) finish: Timestamp,
    pub(crate) data: OpData,
    pub(crate) _client: ClientId,
}

impl OpSpan {
    pub(crate) fn new(
        invoke: Timestamp,
        finish: Timestamp,
        data: OpData,
        client: ClientId,
    ) -> Self {
        OpSpan {
            invoke,
            finish,
            data,
            _client: client,
        }
    }

    pub(crate) fn key(&self) -> KeyType {
        match self.data {
            OpData::Read { key, .. } => key,
            OpData::Write { key, .. } => key,
            OpData::Rmw { key, .. } => key,
        }
    }

    #[allow(dead_code)]
    pub(crate) fn read_only(&self) -> bool {
        match self.data {
            OpData::Read { .. } => true,
            OpData::Write { .. } => false,
            OpData::Rmw { .. } => false,
        }
    }

    pub(crate) fn terminated(&self) -> bool {
        self.finish != 0
    }
}

/// Event type, used only during the parsing of history.
#[derive(Debug, Clone)]
pub(crate) struct Event {
    time: Timestamp,
    etype: EventType,
    client: ClientId,
    opdata: OpData,
}

impl Event {
    pub(crate) fn new(time: Timestamp, etype: EventType, client: ClientId, opdata: OpData) -> Self {
        Event {
            time,
            etype,
            client,
            opdata,
        }
    }
}

/// The collection of per-client queues of operation spans.
/// This is the complete input to feed to the checker algorithm.
#[derive(Debug, Clone)]
pub(crate) struct Timeline {
    pub(crate) queues: Vec<Vec<OpSpan>>,

    // Operation per-type statistics: for each, [invokes, okays, fails]
    pub(crate) stats_ops_sum: usize,
    pub(crate) stats_ops_r: [usize; 3],
    pub(crate) stats_ops_w: [usize; 3],
    pub(crate) stats_ops_cas: [usize; 3],

    // Operation per-key count statistics
    pub(crate) stats_key_ops: HashMap<KeyType, usize>,
    pub(crate) stats_key_min: usize,
    pub(crate) stats_key_med: usize,
    pub(crate) stats_key_avg: usize,
    pub(crate) stats_key_max: usize,

    // Operation per-client count statistics
    pub(crate) stats_cli_min: usize,
    pub(crate) stats_cli_med: usize,
    pub(crate) stats_cli_avg: usize,
    pub(crate) stats_cli_max: usize,
}

impl Timeline {
    pub(crate) fn new(events: Vec<Event>, max_client: ClientId) -> Result<Self, Box<dyn Error>> {
        let mut tl = Timeline {
            queues: vec![vec![]; max_client + 1],
            stats_ops_sum: 0,
            stats_ops_r: [0; 3],
            stats_ops_w: [0; 3],
            stats_ops_cas: [0; 3],
            stats_key_ops: HashMap::new(),
            stats_key_min: usize::MAX,
            stats_key_med: 0,
            stats_key_avg: 0,
            stats_key_max: 0,
            stats_cli_min: usize::MAX,
            stats_cli_med: 0,
            stats_cli_avg: 0,
            stats_cli_max: 0,
        };

        for e in events {
            match e.etype {
                EventType::Invoke => {
                    if (!tl.queues[e.client].is_empty())
                        && tl.queues[e.client].last().unwrap().finish == 0
                    {
                        return Err(format!(
                            "client {} :invoke @ {} when previous op flying",
                            e.client, e.time
                        )
                        .into());
                    }

                    // erase any read results at the time of :invoke
                    let mut opdata = e.opdata;
                    match &mut opdata {
                        OpData::Read { val, .. } => {
                            *val = None;
                            tl.stats_ops_r[0] += 1;
                        }
                        OpData::Write { .. } => {
                            tl.stats_ops_w[0] += 1;
                        }
                        OpData::Rmw { rval, wval, .. } => {
                            *rval = None;
                            *wval = None;
                            tl.stats_ops_cas[0] += 1;
                        }
                    }

                    tl.queues[e.client].push(OpSpan::new(e.time, 0, opdata, e.client));
                }

                EventType::Okay => {
                    if tl.queues[e.client].is_empty()
                        || tl.queues[e.client].last().unwrap().terminated()
                    {
                        return Err(format!(
                            "client {} :ok @ {} when no op is flying",
                            e.client, e.time
                        )
                        .into());
                    }

                    // check data validity
                    let op = tl.queues[e.client].last_mut().unwrap();
                    if !e.opdata.match_previous(&op.data) {
                        return Err(format!(
                            "client {} :ok @ {} op {} mismatching previous {}",
                            e.client, e.time, e.opdata, op.data
                        )
                        .into());
                    }

                    match op.data {
                        OpData::Read { .. } => {
                            tl.stats_ops_r[1] += 1;
                        }
                        OpData::Write { .. } => {
                            tl.stats_ops_w[1] += 1;
                        }
                        OpData::Rmw { .. } => {
                            tl.stats_ops_cas[1] += 1;
                        }
                    }

                    op.finish = e.time;
                    op.data.overwrite_by(e.opdata);

                    tl.stats_key_ops
                        .entry(op.key())
                        .and_modify(|c| *c += 1)
                        .or_insert(1);
                }

                EventType::Fail | EventType::Error => {
                    if tl.queues[e.client].is_empty()
                        || tl.queues[e.client].last().unwrap().terminated()
                    {
                        return Err(format!(
                            "client {} :fail/:info @ {} when no op is flying",
                            e.client, e.time
                        )
                        .into());
                    }

                    match tl.queues[e.client].last().unwrap().data {
                        OpData::Read { .. } => {
                            tl.stats_ops_r[2] += 1;
                        }
                        OpData::Write { .. } => {
                            tl.stats_ops_w[2] += 1;
                        }
                        OpData::Rmw { .. } => {
                            tl.stats_ops_cas[2] += 1;
                        }
                    }

                    // remove failed operation
                    tl.queues[e.client].pop();
                }
            }
        }

        // calculate per-key count statistics
        let key_cnts: Vec<usize> = tl.stats_key_ops.values().copied().collect();
        tl.stats_ops_sum = key_cnts.iter().sum();
        if !key_cnts.is_empty() {
            tl.stats_key_min = *key_cnts.iter().min().unwrap();
            tl.stats_key_max = *key_cnts.iter().max().unwrap();

            tl.stats_key_avg = tl.stats_ops_sum / key_cnts.len();

            let mut sorted = key_cnts.clone();
            sorted.sort_unstable();
            let mid = sorted.len() / 2;
            if sorted.len() % 2 == 0 {
                tl.stats_key_med = (sorted[mid - 1] + sorted[mid]) / 2;
            } else {
                tl.stats_key_med = sorted[mid];
            }
        }

        // calculate per-client count statistics
        let cli_cnts: Vec<usize> = tl.queues.iter().map(|q| q.len()).collect();
        if !cli_cnts.is_empty() {
            tl.stats_cli_min = *cli_cnts.iter().min().unwrap();
            tl.stats_cli_max = *cli_cnts.iter().max().unwrap();

            tl.stats_cli_avg = tl.stats_ops_sum / cli_cnts.len();

            let mut sorted = cli_cnts.clone();
            sorted.sort_unstable();
            let mid = sorted.len() / 2;
            if sorted.len() % 2 == 0 {
                tl.stats_cli_med = (sorted[mid - 1] + sorted[mid]) / 2;
            } else {
                tl.stats_cli_med = sorted[mid];
            }
        }

        Ok(tl)
    }

    #[inline]
    pub(crate) fn num_keys(&self) -> usize {
        self.stats_key_ops.len()
    }

    #[inline]
    pub(crate) fn num_clients(&self) -> usize {
        self.queues.len()
    }
}

/// Ranks of supported consistency levels. Currently only a chain-hierarchy of
/// levels supported, which conveniently covers the four most common levels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum Consistency {
    Weak = 0,
    // TODO: currently only linearizability exploration supported
    // Eventual = 1,
    // Causal = 2, // actually causal+
    // Sequential = 3,
    Linearizable = 4,
}
