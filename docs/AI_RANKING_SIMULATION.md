# ClipForge ranking strategy simulation

Date: 2026-11-04

## Purpose and limits

This is a reproducible-style synthetic comparison of ten highlight-ranking weight profiles. It is **not** a test on real videos, real audience retention, or a physical Android device. The relevance labels are synthetic, so the results help choose a conservative starting profile but do not establish virality or production accuracy.

## Experiment

- 100 independent runs.
- 100 candidate moments per run (10,000 candidate moments total).
- Each candidate receives eight normalized features in [0, 1]: speech density, hook terms, punctuation, vocabulary variety, context completeness, novelty, clarity, and opening strength.
- A synthetic relevance target is formed from a weighted combination of those features plus Gaussian noise. The target intentionally gives more influence to context, novelty, clarity, and opening quality than to raw speech density.
- Precision@10 measures the fraction of the ten highest-ranked candidates in the top synthetic relevance quartile.
- NDCG@10 measures ranking quality against the synthetic relevance target.

## Ten profiles compared

| Profile | Strategy |
|---|---|
| V1 | Density-first |
| V2 | Hook-first |
| V3 | Balanced |
| V4 | Context-first |
| V5 | Novelty-first |
| V6 | Clarity-first |
| V7 | Opening-first |
| V8 | Engagement-proxy |
| V9 | Robust-balanced |
| V10 | Context + novelty + clarity |

## Aggregate results

| Profile | Precision@10 | NDCG@10 |
|---|---:|---:|
| V1 | 49.0% | 0.7825 |
| V2 | 60.3% | 0.8389 |
| V3 | 79.6% | 0.9029 |
| V4 | 78.1% | 0.8992 |
| V5 | 77.7% | 0.8978 |
| V6 | 76.5% | 0.8916 |
| V7 | 75.0% | 0.8923 |
| V8 | 66.4% | 0.8569 |
| **V9** | **79.4%** | **0.9062** |
| V10 | 78.5% | 0.9014 |

## Selected production profile

**V9 — Robust-balanced** is the default because it achieved the highest NDCG@10 while remaining close to the highest Precision@10. It gives more weight to context, novelty, clarity, and opening quality than to raw speech density, hook words, or punctuation.

The implementation remains deterministic and offline. V1–V10 are exposed as named profiles for future testing; V9 is the default.

## Next validation gate

Before treating these numbers as real product performance, run a labeled evaluation set of actual videos. Human reviewers should mark whether each proposed clip is self-contained, understandable without surrounding context, non-duplicative, and worth keeping. Then compare precision@5/10, recall, NDCG, processing time, memory use, and device temperature on supported Android hardware.
