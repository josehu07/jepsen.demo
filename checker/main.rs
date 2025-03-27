//! Rust implementation of a checker based on the SOP model.
//!
//! The current implementation uses an offline procesesing style just like
//! original Jepsen, but should be easy to adapt to an online style.

use std::error::Error;
use std::path::Path;
use std::process;
use std::time::Instant;

use clap::Parser;

mod store;
use store::parse_history;

mod types;
use types::Timeline;

/// Command line arguments.
#[derive(Parser, Debug)]
struct Args {
    /// Jepsen test store directory.
    #[arg(short, long)]
    test_dir: String,
}

// Return codes.
const CHECK_PASS: i32 = 0;
const CHECK_FAIL: i32 = 1;
const CHECK_ERROR: i32 = 101; // since panicking produces exit code 101

/// Inner main function.
fn main_inner() -> Result<bool, Box<dyn Error>> {
    let args = Args::parse();
    eprintln!("Test directory: '{}'", args.test_dir);

    let (events, max_client) = parse_history(Path::new(&args.test_dir))?;
    if events.is_empty() {
        return Err("input history is empty".into());
    }

    let start_ts = Instant::now();

    let timeline = Timeline::new(events, max_client)?;
    println!(
        "Parsed timeline: {} clients, {} total ops",
        timeline.num_clients(),
        timeline.total_ops()
    );
    println!("Call stats:  {:5}  {:5}  {:5}", " call", " okay", " fail");
    println!(
        "       read  {:5}  {:5}  {:5}",
        timeline.stats_r[0], timeline.stats_r[1], timeline.stats_r[2]
    );
    println!(
        "      write  {:5}  {:5}  {:5}",
        timeline.stats_w[0], timeline.stats_w[1], timeline.stats_w[2]
    );
    println!(
        "        cas  {:5}  {:5}  {:5}",
        timeline.stats_cas[0], timeline.stats_cas[1], timeline.stats_cas[2]
    );

    let finish_ts = Instant::now();
    let elapsed = finish_ts.duration_since(start_ts);
    println!(
        "Time spent excluding I/O: {:.2} msecs",
        (elapsed.as_nanos() as f64) / 1_000_000.0
    );

    unimplemented!()
}

/// Error code returned should follows this convention:
///   - 0: check passed
///   - 1: check failed
///   - higher: error in checker, result unknown
fn main() {
    match main_inner() {
        Ok(true) => process::exit(CHECK_PASS),
        Ok(false) => process::exit(CHECK_FAIL),
        Err(err) => {
            eprintln!("Error: {}", err);
            process::exit(CHECK_ERROR)
        }
    }
}
