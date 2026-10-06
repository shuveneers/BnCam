"""Compatibility entry point: one manual W24 session, then stop. No v1 chain."""
from pathlib import Path
import sys
from .session_train import main


if __name__=='__main__':
    latest=Path('build/bnc-neural-v2/training/w24/checkpoints/latest.pt')
    if '--resume' not in sys.argv and latest.exists():sys.argv.extend(['--resume',str(latest)])
    main()
