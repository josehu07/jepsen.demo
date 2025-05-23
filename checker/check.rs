//! Demonstrative checker implementation.

use std::error::Error;

use crate::types::Timeline;

/// Ranks of supported consistency levels. Currently only a chain-hierarchy of
/// levels supported, which conveniently covers the four most common levels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ConsistencyLevel {
    Weak = 0,
    Eventual = 1,
    Causal = 2, // actually causal+
    Sequential = 3,
    Linearizable = 4,
}

/// Checker states.
#[derive(Debug)]
pub(crate) struct Checker {}

impl Checker {
    /// Create a new checker.
    pub(crate) fn new() -> Self {
        Checker {}
    }

    /// Check the history.
    pub(crate) fn check(&mut self, timeline: Timeline) -> Result<ConsistencyLevel, Box<dyn Error>> {
        Ok(ConsistencyLevel::Weak)
    }
}
