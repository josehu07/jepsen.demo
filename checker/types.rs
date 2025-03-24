//! Definition of fundamental types for consistency checking.

use std::collections::VecDeque;
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

/// Event type enum.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EventType {
    Invoke, // :invoke
    Okay,   // :ok
    Fail,   // :fail
    Error,  // :info with :error
}

/// Operation type and data. Option fields are `None` when the operation is
/// on the fly and result values are not known yet. A value could also remain
/// `None` after the timeline has parsed due to failed read.
#[derive(Debug, Clone)]
pub(crate) enum OpData {
    Read {
        key: KeyType,
        val: Option<ValType>,
    },
    Write {
        key: KeyType,
        val: ValType,
    },
    /// Any general operation fits RMW (read-modify-write). A common example
    /// beyond regular reads/writes is CAS (compare-and-swap).
    /// In our current implementation, only :cas is supported for this type.
    Rmw {
        key: KeyType,
        rval: Option<ValType>,
        wval: Option<ValType>, // function from `rval` to `wval` assumed correct
    },
}

impl fmt::Display for OpData {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            OpData::Read { key, val } => {
                write!(
                    f,
                    "R_{}:{}",
                    key,
                    val.as_ref().map(|v| v.to_string()).unwrap_or("-".into())
                )
            }
            OpData::Write { key, val } => write!(f, "W_{}<{}", key, val),
            OpData::Rmw { key, rval, wval } => {
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
            (OpData::Write { key, val }, OpData::Write { key: k, val: v }) => key == k && val == v,
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
    invoke: Timestamp,
    finish: Timestamp,
    data: OpData,
}

impl OpSpan {
    pub(crate) fn new(invoke: Timestamp, finish: Timestamp, data: OpData) -> Self {
        OpSpan {
            invoke,
            finish,
            data,
        }
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
    queues: Vec<VecDeque<OpSpan>>,
}

impl Timeline {
    pub(crate) fn new(events: Vec<Event>, max_client: ClientId) -> Result<Self, Box<dyn Error>> {
        let mut tl = Timeline {
            queues: vec![VecDeque::new(); max_client + 1],
        };

        for e in events {
            match e.etype {
                EventType::Invoke => {
                    if (!tl.queues[e.client].is_empty())
                        && tl.queues[e.client].back().unwrap().finish == 0
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
                        }
                        OpData::Rmw { rval, wval, .. } => {
                            *rval = None;
                            *wval = None;
                        }
                        _ => {}
                    }

                    tl.queues[e.client].push_back(OpSpan::new(e.time, 0, opdata));
                }

                EventType::Okay => {
                    if tl.queues[e.client].is_empty()
                        || tl.queues[e.client].back().unwrap().finish != 0
                    {
                        return Err(format!(
                            "client {} :ok @ {} when no op is flying",
                            e.client, e.time
                        )
                        .into());
                    }

                    // check data validity
                    let op = tl.queues[e.client].back_mut().unwrap();
                    if !e.opdata.match_previous(&op.data) {
                        return Err(format!(
                            "client {} :ok @ {} op {} mismatching previous {}",
                            e.client, e.time, e.opdata, op.data
                        )
                        .into());
                    }

                    op.finish = e.time;
                    op.data.overwrite_by(e.opdata);
                }

                EventType::Fail | EventType::Error => {
                    if tl.queues[e.client].is_empty()
                        || tl.queues[e.client].back().unwrap().finish != 0
                    {
                        return Err(format!(
                            "client {} :fail/:info @ {} when no op is flying",
                            e.client, e.time
                        )
                        .into());
                    }

                    // check data validity
                    let op = tl.queues[e.client].back_mut().unwrap();
                    if !e.opdata.match_previous(&op.data) {
                        return Err(format!(
                            "client {} :fail/:info @ {} op {} mismatching previous {}",
                            e.client, e.time, e.opdata, op.data
                        )
                        .into());
                    }

                    op.finish = e.time;
                }
            }
        }

        Ok(tl)
    }

    #[inline]
    pub(crate) fn num_clients(&self) -> usize {
        self.queues.len()
    }

    #[inline]
    pub(crate) fn total_ops(&self) -> usize {
        self.queues.iter().map(|q| q.len()).sum()
    }
}
