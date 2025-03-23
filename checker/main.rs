//! Rust implementation of a checker based on the SOP model.

use std::process;

use clap::Parser;

/// Command line arguments.
#[derive(Parser, Debug)]
struct Args {
    /// Jepsen test store directory.
    #[arg(short, long)]
    test_dir: String,
}

fn main() {
    let args = Args::parse();
    println!("Test directory: '{}'", args.test_dir);

    // exit with code 1
    process::exit(1);
}
