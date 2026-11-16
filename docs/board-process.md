# Board process

Status: approved 2026-11-13. Rollout (§7) has not started; the board does not follow this layout yet.

The [prooph board](https://flow.prooph-board.com/) workspace `ec484260-1c12-45d7-a8a7-f0af8e2fc520` is the source of truth for what Tover should do. Business and tech people discuss there; agents read and write it through the prooph board MCP tools. This document is the whole process. Code and other docs never override the board; when they disagree, fix the board first, then the code.

## 1. Where things live on the board

| Place | Holds | Shared across chapters |
| --- | --- | --- |
| Element `description` | Two or three sentences: what this element does at this step. | No, per placement. |
| Element `details` | The data model: fields and the rules that hold everywhere the element appears (§2). | Yes. Elements with the same name, type and context share one `details`. |
| Slice `details` | Decisions for this step and its Given/When/Then examples. | No. |
| Hotspot `description` | An open question. Stays until decided, then is removed and the decision goes into details. | No. |
| Task comment on a slice or element | Feedback or a requested change. Resolved when handled. | No. |
| Chapter `00 · Common contracts` | Rules that apply to every command (sign-in and permissions at commit, retries, workspace retargeting). | Referenced by name. |

Rules:

- One fact, one home. A rule is written once: in the shared `details` of the element it belongs to, or in chapter 00. Descriptions do not repeat it.
- No prose cross-references to rules, such as "permissions at commit (chapter 07)". When a chapter depends on a rule or data from another chapter, place the same element there (`copy_element`, same name, type and context) or name it exactly in backticks, e.g. `Select Workspace`. Both are found by one name search.
- Pointing the reader to a whole journey is fine and uses the full chapter title: "opens through 07 · Switch workspace".
- Slice examples go under a `## Examples` heading in slice details, one bold title per example followed by Given / When / Then bullets. Each example becomes one scenario test.
- Renaming an element breaks its links. Rename every placement in the same change.

## 2. Data model in element details

Shared `details` use one fixed layout per element type so people and agents read them the same way. Create these as board templates (prooph board UI; the MCP tools can read templates but not create them) so the board enforces the layout.

Event and Information:

```markdown
## Fields
| Field | Type | Required | Meaning |

## Rules
- Invariants that hold wherever this appears.
```

Command:

```markdown
## Input
| Field | Type | Required | Meaning |

## Rules
- Preconditions checked at commit.

## Rejections
- Named reasons the command is refused.
```

In the templates, instruction lines must be `%% ... %%` placeholders. Plain instruction text is treated as fixed and would be required verbatim in every element.

Every identifier a command needs (the attempt id for retries, ids proposed by the client) is a row in its Input table. If the table cannot be filled from the board, that is a gap in the board, not something to settle in code.

Types are plain words: text, whole number, instant, id of `<Element or entity>`, one of `A | B`. The Malli schema in code is derived from this table, never the other way round.

Code names follow the board name: `Warehouse Registered` is `::warehouse-registered` in the namespace of its context. No mapping table is kept.

## 3. Slice lifecycle

Status lives on the slice. A chapter is done when every slice is `deployed`.

| Status | Meaning | Who moves it here |
| --- | --- | --- |
| `draft` | Being written or changed. Not to be implemented. | Anyone |
| `planned` | People have read and approved it. | A person only |
| `in-progress` | An agent or developer is translating it into Bel declarations and scenario tests. | Implementer |
| `ready` | Implemented, checks pass, waiting for testers. | Implementer |
| `blocked` | An open task comment needs a board decision. | Anyone who files the task |
| `reviewed` | Testers accepted the behavior. | A person only |
| `deployed` | Released. | A person only |

Agents never set `planned`, `reviewed` or `deployed`.

Implementing a `planned` slice follows [bel-architecture.md §8](bel-architecture.md): scenarios from the slice examples, declarations, pure handler, then store details.

Feedback during testing goes on the board as a task comment on the slice or element, and the slice becomes `blocked`. The decision is written into details, the task is resolved, a person sets `planned` again, and the implementer picks it up.

## 4. Changing something already approved

1. Edit the shared `details` (or the slice details) on the board.
2. Find what is involved: `search_elements` with the exact element name and `detail: "none"`. Every hit is an affected chapter and slice.
3. On each affected slice that is `planned` or later, add a task comment naming the change and set it to `blocked`.
4. From there it is the normal loop in §3.

Nothing else is re-read. A change to chapter 00 affects every command; treat it as a deliberate, rare event and review it with a person before step 3.

## 5. How agents read the board

- Never load the whole board. Never call `search_elements` without `chapter_id` or an exact name, and use `detail: "none"` unless descriptions are needed.
- To work a slice: `get_chapter` with `slice_ids`, then `get_element` for elements whose shared details matter.
- To see a chapter's shape: `get_chapter` with `structure_only: true`.
- To catch up: `list_changelog_events` with `since` set to the instant in [board-sync.edn](board-sync.edn), `detail: "summary"`. Read only the slices that changed, then update the instant.
- `update_slice` replaces slice details whole; read before writing. `append_details` (elements and slices) and `append_description` add without a read; prefer them.
- Full `get_chapter` on a chapter with long slice details can time out. Retry with `slice_ids` or `structure_only`.

## 6. Repo side

- This file is the only process description. `AGENTS.md` points here.
- `docs/board-sync.edn` holds `{:synced-at "<instant>"}`: board changes up to this instant have been read and acted on.
- Docs that restate board content (`bel-onboarding-mapping.md` chapter table, `bel-architecture.md` §12.5 decided hotspots) are not extended. Each is deleted once its content is in board details.

## 7. Rollout

1. Create the three templates in the prooph board UI.
2. Add chapter `00 · Common contracts` and move the repeated command rules there.
3. Pilot on chapter 04 (Create a workspace), which already has an executable slice: fill details, replace prose references, add scenarios, walk it through every status. Board side done 2026-11-13 (details and examples). Code side 2026-11-14: the command and read slices are `ready` with scenario tests in `test/tover/workspaces/create_test.clj`; the form slice stays `planned` because the isolated Bel slice has no form view yet.
4. Adjust this document from what the pilot shows, then migrate chapters in the order they are implemented, not all at once.

## Not doing

- No generated index of the board in the repo. Name search answers the same question without going stale. Add one only if search proves too slow or too noisy.
- No milestones yet. Add when work is delivered in named batches.
- No approval tooling beyond slice status and task comments.

## Approaches considered

- **Data model on the board in linked element details (chosen).** One edit reaches every chapter, impact is a name search, business people see the same schema.
- **Generated repo index only.** No board work, but schemas stay in prose and the index drifts between syncs.
- **Code as the data model with links back to the board.** Hides schemas from business people and makes code the truth, which is the opposite of the goal.
