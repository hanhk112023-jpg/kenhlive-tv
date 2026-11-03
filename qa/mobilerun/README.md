# Mobilerun Pilot — AI regression tests for KenhLive

Pilot integration of [Mobilerun](https://github.com/droidrun/mobilerun) (LLM-agnostic
mobile agent) as a second, scenario-based UI test layer next to the hand-rolled
QA-Suite. It drives the Android TV emulator on GitHub Actions with natural-language
tasks and reports a per-scenario pass/fail summary.

## How it runs

- Workflow: `.github/workflows/mobilerun-pilot.yml` (manual `workflow_dispatch`).
- It mirrors QA-Suite's environment: debug APK build, VN proxy (mihomo), KVM TV
  emulator (API 30, tv_1080p), and the same **Preflight** that picks the best
  vision model on the Meteor gateway.
- The gateway is OpenAI-compatible, so Mobilerun uses provider `OpenAILike` with
  `base_url` = Meteor and the **existing** `METEOR_API_KEY` secret passed as
  `OPENAI_API_KEY` — no new API key is needed.
- `run_pilot.sh` installs the Mobilerun Portal on the emulator (`mobilerun setup`),
  then runs every file in `scenarios/` as one `mobilerun run` task
  (`--vision --reasoning --steps 45`, 20-minute timeout each), saves the log,
  a final screenshot per scenario, and `summary.md` to the `mobilerun-report`
  artifact.

## Scenarios

Each scenario encodes one real user-feedback regression point (TungNguyen's
reports) with an explicit PASS/FAIL criterion in the prompt:

1. `01-posters` — Phim home rows show real poster artwork, not blank placeholders.
2. `02-phim-bo-grid` — "Phim Bộ" filter shows a vertical, scrollable poster grid
   that loads more films (not a single horizontal row).
3. `03-episode-chips` — episode chips show full "Tập N" labels, uniform size,
   focused chip has a bright orange ring.
4. `04-player-bar` — player control bar shows Thoát / play-pause / seek /
   "Danh sách tập" with no hint text overlapping the buttons.
5. `05-seek-stable` — two forward seeks advance playback ~20s without snapping back.

## Config

`config.yaml` is the Mobilerun config template: all LLM profiles point to
`OpenAILike` + Meteor with model token `__MODEL__`, which the runner replaces
with the Preflight-picked model at runtime. `api_key_source: env` reads
`OPENAI_API_KEY` from the environment.

## Local run (with a device/emulator attached)

```bash
pip install mobilerun
export OPENAI_API_KEY=<meteor key>
sed "s/__MODEL__/<vision model id>/g" config.yaml > config.runtime.yaml
mobilerun setup
mobilerun run "$(cat scenarios/01-posters.txt)" -c config.runtime.yaml --vision --reasoning --steps 45
```
