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
use types::{Consistency, Timeline};

mod check;
use check::Checker;

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
    let start_ts = Instant::now();
    let args = Args::parse();
    eprintln!("Test directory: '{}'", args.test_dir);

    let (events, max_client) = parse_history(Path::new(&args.test_dir))?;
    if events.is_empty() {
        return Err("input history is empty".into());
    }

    let timeline = Timeline::new(events, max_client)?;
    print_timeline_stats(&timeline);

    let check_ts = Instant::now();
    let mut checker = Checker::new(timeline);
    let level = checker.check()?;
    let finish_ts = Instant::now();

    println!(
        "Checker result: {}",
        if level == Consistency::Linearizable {
            "== Linearizable, nice 👌".into()
        } else {
            format!(">= {:?} but < Linearizable 🤔", level)
        }
    );
    println!("    based on this specific history,");
    println!("    could just be a loose upper bound");

    println!(
        "Time spent excluding I/O: {:.2} msecs",
        (finish_ts.duration_since(check_ts).as_nanos() as f64) / 1_000_000.0
    );
    println!(
        "Time spent in Rust total: {:.2} msecs",
        (finish_ts.duration_since(start_ts).as_nanos() as f64) / 1_000_000.0
    );

    Ok(level == Consistency::Linearizable)
}

fn print_timeline_stats(timeline: &Timeline) {
    println!(
        "Parsed timeline: {} clients, {} keys, {} total ops",
        timeline.num_clients(),
        timeline.num_keys(),
        timeline.stats_ops_sum,
    );

    println!("Call stats:  {:>5}  {:>5}  {:>5}", "call", "okay", "fail");
    println!(
        "       read  {:5}  {:5}  {:5}",
        timeline.stats_ops_r[0], timeline.stats_ops_r[1], timeline.stats_ops_r[2]
    );
    println!(
        "      write  {:5}  {:5}  {:5}",
        timeline.stats_ops_w[0], timeline.stats_ops_w[1], timeline.stats_ops_w[2]
    );
    println!(
        "        cas  {:5}  {:5}  {:5}",
        timeline.stats_ops_cas[0], timeline.stats_ops_cas[1], timeline.stats_ops_cas[2]
    );

    println!(
        "Keyops dist.:  {:>4}  {:>4}  {:>4}  {:>4}",
        "min", "med", "avg", "max"
    );
    println!(
        "               {:4}  {:4}  {:4}  {:4}",
        timeline.stats_key_min,
        timeline.stats_key_med,
        timeline.stats_key_avg,
        timeline.stats_key_max
    );

    println!(
        "Client dist.:  {:>4}  {:>4}  {:>4}  {:>4}",
        "min", "med", "avg", "max"
    );
    println!(
        "               {:4}  {:4}  {:4}  {:4}",
        timeline.stats_cli_min,
        timeline.stats_cli_med,
        timeline.stats_cli_avg,
        timeline.stats_cli_max
    );
}

/// Error code returned should follows this convention:
///   - 0: linearizability passed
///   - 1: not linearizable, but may satisfy a weaker level (check output)
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
