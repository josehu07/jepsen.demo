//! IO-related helpers for interacting with the Jepsen store directory.

use std::error::Error;
use std::fs::File;
use std::io::{self, BufRead};
use std::path::Path;

use crate::types::{ClientId, Event, EventType, KeyType, OpData, Timestamp, UniqueTag, ValType};

/// History edn file name.
const HISTORY_FILE: &str = "history.edn";

// Parsing methods for the fundamental types...
impl EventType {
    pub(crate) fn from_type(s: &str) -> Result<Self, Box<dyn Error>> {
        match s {
            ":invoke" => Ok(EventType::Invoke),
            ":ok" => Ok(EventType::Okay),
            ":fail" => Ok(EventType::Fail),
            ":info" => Ok(EventType::Error),
            _ => Err(format!("unknown event type: {}", s).into()),
        }
    }
}

impl OpData {
    pub(crate) fn from_type(s: &str) -> Result<Self, Box<dyn Error>> {
        match s {
            ":read" => Ok(OpData::Read {
                key: 0,
                val: None,
                tag: None,
            }),
            ":write" => Ok(OpData::Write {
                key: 0,
                val: 0,
                tag: 0,
            }),
            ":cas" => Ok(OpData::Rmw {
                key: 0,
                rval: None,
                rtag: None,
                wval: None,
                wtag: None,
            }),
            _ => Err(format!("unknown operation type: {}", s).into()),
        }
    }

    pub(crate) fn fill_values(&mut self, s: &str) -> Result<(), Box<dyn Error>> {
        match self {
            OpData::Read { key, val, .. } => {
                if let Some((k, v)) = s
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .split_once(' ')
                {
                    *key = k.parse::<KeyType>()?;
                    if v.trim() == "nil" {
                        *val = None;
                    } else {
                        *val = Some(v.parse::<ValType>()?);
                    }
                } else {
                    return Err(format!("invalid :value for :read: {}", s).into());
                }
            }

            OpData::Write { key, val, .. } => {
                if let Some((k, v)) = s
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .split_once(' ')
                {
                    *key = k.parse::<KeyType>()?;
                    *val = v.parse::<ValType>()?;
                } else {
                    return Err(format!("invalid :value for :write: {}", s).into());
                }
            }

            OpData::Rmw {
                key, rval, wval, ..
            } => {
                if let Some((k, vp)) = s
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .split_once(' ')
                {
                    *key = k.parse::<KeyType>()?;
                    if let Some((rv, wv)) = vp
                        .trim_start_matches('[')
                        .trim_end_matches(']')
                        .split_once(' ')
                    {
                        *rval = Some(rv.parse::<ValType>()?);
                        *wval = Some(wv.parse::<ValType>()?);
                    } else {
                        return Err(format!("invalid :value for :cas: {}", s).into());
                    }
                } else {
                    return Err(format!("invalid :value for :cas: {}", s).into());
                }
            }
        }

        Ok(())
    }

    pub(crate) fn fill_tstags(&mut self, s: &str) -> Result<(), Box<dyn Error>> {
        match self {
            OpData::Read { tag, .. } => {
                if s.trim() == "nil" {
                    *tag = None;
                } else {
                    *tag = Some(s.parse::<UniqueTag>()?);
                }
            }

            OpData::Write { tag, .. } => {
                *tag = s.parse::<UniqueTag>()?;
            }

            OpData::Rmw { rtag, wtag, .. } => {
                if let Some((rt, wt)) = s
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .split_once(' ')
                {
                    if rt.trim() == "nil" {
                        *rtag = None;
                    } else {
                        *rtag = Some(rt.parse::<UniqueTag>()?);
                    }
                    if wt.trim() == "nil" {
                        *wtag = None;
                    } else {
                        *wtag = Some(wt.parse::<UniqueTag>()?);
                    }
                } else {
                    return Err(format!("invalid :tstag for :cas: {}", s).into());
                }
            }
        }

        Ok(())
    }
}

/// Inner parsing helper for a segment. Returns false if the current line should
/// be skipped.
/// NOTE: should've made this into a parser struct, but anyways...
#[allow(clippy::too_many_arguments)]
fn parse_segment(
    field: &str,
    stuff: &str,
    last_index: &mut i64,
    last_time: &mut Timestamp,
    max_client: &mut ClientId,
    time: &mut Option<Timestamp>,
    etype: &mut Option<EventType>,
    client: &mut Option<ClientId>,
    op: &mut Option<OpData>,
) -> Result<bool, Box<dyn Error>> {
    match field {
        ":index" => {
            let this_index = stuff.parse::<i64>()?;
            if this_index <= *last_index {
                return Err(format!("index {} <= last index {}", this_index, last_index).into());
            }
            *last_index = this_index;
        }

        ":time" => {
            let this_time = stuff.parse::<u64>()?;
            if this_time <= *last_time {
                return Err(
                    format!("timestamp {} <= last timestamp {}", this_time, last_time).into(),
                );
            }
            *last_time = this_time;
            *time = Some(this_time);
        }

        ":type" => {
            *etype = Some(EventType::from_type(stuff)?);
        }

        ":process" => {
            if stuff.trim_start().starts_with(':') {
                // not a regular client
                return Ok(false);
            }

            let this_client = stuff.parse::<ClientId>()?;
            if this_client > *max_client {
                *max_client = this_client;
            }
            *client = Some(this_client);
        }

        ":f" => {
            *op = Some(OpData::from_type(stuff)?);
        }

        ":value" => {
            if let Some(op) = op.as_mut() {
                op.fill_values(stuff)?;
            } else {
                return Err("missing op type :f for :value".into());
            }
        }

        ":tstag" => {
            if let Some(op) = op.as_mut() {
                op.fill_tstags(stuff)?;
            } else {
                return Err("missing op type :f for :tstag".into());
            }
        }

        // skip other fields
        _ => {}
    }

    Ok(true)
}

/// Reads the history file into a stream of events. Returns the vec of events
/// and the maximum client ID found.
pub(crate) fn parse_history(test_dir: &Path) -> Result<(Vec<Event>, ClientId), Box<dyn Error>> {
    let file = File::open(test_dir.join(HISTORY_FILE))?;
    let reader = io::BufReader::new(file);

    let mut events = Vec::new();
    let mut last_index: i64 = -1;
    let mut last_time: Timestamp = 0;
    let mut max_client: ClientId = 0;

    let mut time = None;
    let mut etype = None;
    let mut client = None;
    let mut op = None;

    for line in reader.lines() {
        let line = line?;
        let line = line.trim_start_matches('{').trim_end_matches('}');
        if line.is_empty() {
            continue;
        }

        // some events are not from clients but from e.g. nemesis, need to
        // skip those
        let mut skip = false;

        for seg in line
            .split(',')
            .map(|s| s.trim())
            .filter(|s| s.starts_with(':'))
        {
            if let Some((field, stuff)) = seg.split_once(' ') {
                match parse_segment(
                    field,
                    stuff,
                    &mut last_index,
                    &mut last_time,
                    &mut max_client,
                    &mut time,
                    &mut etype,
                    &mut client,
                    &mut op,
                ) {
                    Ok(true) => {}
                    Ok(false) => {
                        skip = true;
                        break;
                    }
                    Err(err) => {
                        eprintln!("Skip line due to segment: {}: {}", seg, err);
                        skip = true;
                        break;
                    }
                }
            } else {
                eprintln!("Skip line due to invalid segment: {}", seg);
                skip = true;
                break;
            }
        }

        if skip {
            continue;
        }

        // compose line into an event
        if let (Some(time), Some(etype), Some(client), Some(op)) =
            (time.take(), etype.take(), client.take(), op.take())
        {
            events.push(Event::new(time, etype, client, op));
        } else {
            return Err(format!("missing event field(s) in line: {}", line).into());
        }
    }

    Ok((events, max_client))
}
