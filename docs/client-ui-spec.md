# Tover client UI specification

Status: agreed first tester workflow, 2026-10-21. Describe user needs, business rules and observable outcomes. [Product requirements](product-requirements.md) define inventory semantics. This specification replaces simulated grid submission and the browser's local history baseline.

## Shared workspace

Stock and Operations share navigation, header, theme and one combined search/add-operation input. Show the active workspace and authenticated user. Users can log out, switch memberships, open Team and manage invitations when authorized.

Search, filtering and sorting return server results. Keep familiar spreadsheet editing, keyboard focus and row selection. Browser adapters render state and forward editor events; all business effects and accepted history come from the server. Remove demo switches, fabricated operations, simulated Submit and local business calculations.

Keep the original smart-input appearance. Plain search defaults to Goods on both pages; Operations searches its item names. The smart input adds only begins-with filters. Show active filters below it with their columns, operators, AND/OR grouping, removal and Reset all. Header popups retain complex conditions and use the same server filter state. Replacing complex conditions through the input requires an explicit replacement choice.

The empty suggestion menu shows only Add operation; typing still suggests matching search columns. Typing or selecting `add` first shows operation types, then that type's unfilled fields and values. Selecting an existing item in either editor prefills its name, article, barcode and unit. Fill a blank line location only when one accepted location has positive stock. Preserve entered quantity/location; leave transfer locations explicit. Filled/default fields remain editable and removable chips. Save unfinished smart-input operations privately for their author. Done becomes available when required fields are valid; Cmd/Ctrl-Enter saves the active value and completes the line. Done shares the entry as pending; it does not accept history. Resume private work after refresh. Cancel addition returns to search and discards only the current unfinished addition, preserving filters and shared changes. Escape clears an unfinished field first; an empty Escape cancels the addition. Cmd/Ctrl-K focuses the input from any workbench control without clearing its state; leaving a cell saves through its normal blur action. Do not put separate operation buttons, type selects or sort selects beside the input.

Keep sortable table headers and the original spreadsheet styling, with 32px rows and 34px headers. Save column widths and header order per account and table on the server, across refreshes, browsers and workspaces. Drag data headers to reorder; selection and Actions stay at the edges. Preserve merged groups and active edits. Apply updates after dragging finishes; retain failed settings with a Retry action. Personal layouts are separate from inventory history. Show compact modified/cancelled/added counts and icon actions above the table. Keep the empty screen quiet. Pending Stock edits appear in the table; the editable review opens above it. Do not add draft cards or forms below the table.

Load accepted Stock and Operations in server-defined windows around the visible region. The scrollbar represents the whole filtered result. Keep complete operation groups and absolute row numbers; never split their merged cells. Preserve the visible row and pixel offset during expansion, loading and realtime refresh. Fetch operation details only when expanded. Offscreen rows remain inventory, not deletions; retain edits and history while their windows are unloaded. Failed reads keep existing data and offer Retry. Changes to submit and nested item-history tables keep their natural height.

Display dates in the viewer's local format and timezone. Date editors use a local date and time picker; storage and business ordering retain UTC instants. Keep revision numbers internal throughout the interface. Keep primary edit fields together and optional item and amount details secondary.

## Stock

Show current accepted quantities grouped by item and location. Article/barcode describe the item; internal IDs stay out of normal table presentation. Different suppliers do not create separate item identities. Keep accepted values distinct from pending corrections.

Inline edits propose manual corrections with an editable field-specific default reason. Preserve user-written reasons on subsequent edits. Add row proposes a declaration of preexisting stock with a reason. Do not label it Purchase or invent unknown origin history. Editing descriptive attributes preserves the item's history.

Stock uses a spreadsheet viewport that fills the available screen. Show the stock quantity. Accepting a purchase adds its item quantities directly; do not show separate arrival rows or delivery statuses. Keep its headers visible and horizontal scrolling for wide tables. The Goods chevron toggles expansion. Expand a row to fetch its item history immediately beneath that row. Switch between History and Lineage; show one view at a time and retain it on refresh. One spreadsheet combines the item's records, occurrence and recording dates, status, actor, reason and effective quantity changes. Show the actor's display name, then email, then “Unavailable user.” Keep Refresh and Close as right-aligned icons. Preserve superseded and cancelled revisions without counting their effects again. The Gantt shows the item's own history in separate location lanes. Show dated production parents as compact strips above the produced item inside the timeline, ending where their production bars begin, with item names and consumed quantities; omit parent balance timelines. Label timeline bars with operation types; keep balances in tooltips and selected details. Click bars or markers for compact authorized historical details, without a legend or always-visible operation summaries. Pooled stock must not imply attribution to a particular receipt or individual unit.

## Operations

Show accepted business-operation history and clearly separated pending entries. Operations uses the same spreadsheet viewport as Stock, retaining horizontal scrolling. Each operation forms one group with shared cells spanning its item lines. Keep the operation chevron and label on one line; truncate narrow labels. Omit explanatory section rows from accepted operations. Show production inputs in red and outputs in green. Purchases have one ordinary item list. Use a compact plus control with a localized accessible label to add an item. Accepting a purchase increases stock directly. Historical records keep their recorded stock effects. Merge commercial amounts once per operation. Keep draft add/remove line controls and operation discard in the rightmost Actions column. Never merge unrelated operations because their values match. Add row creates a draft operation using the same server editor as the combined input.

| Operation | Editor needs |
|---|---|
| Purchase | Party, item lines, quantities, locations and optional amounts. Accept the purchase to increase stock. |
| Return | Customer/supplier direction, item quantities, locations and optional original operation. |
| Transfer | Item quantities and paired source/destination locations. |
| Production | Input and output item/quantity/location lists; support multiple inputs and outputs. |
| Defect | Item quantities, location and reason. |
| Sale | Party, item quantities, source location and optional amounts. |
| Write-off | Item quantities, location and reason. |
| Payment | Incoming/outgoing direction, party, exact amount, currency and optional purchase/sale reference. |

Expand an operation for compact recorded time, submitter, reason and correction/cancellation actions. Show creator/editor only when they add a different person. Omit duplicated item tables and nested audit sections. Preserve accepted historical records internally and show item history on Stock. Provide corrections/cancellations through draft review. Do not show a lineage view on Operations; lineage belongs to expanded item history on Stock.

## Shared draft and review

All active members edit the workspace's durable pending draft. Stock Add row and existing pending edits remain shared. Private smart-input compositions do not affect counts, stock, suggestions for other members or review until Done. Completed field/cell edits save on Enter, Tab or leaving the field. Display Saving while pending, Saved after acknowledgment and Failed if rejected or interrupted. An acknowledged save survives refresh, tab closure, navigation and application restart. Stale edits show the saved and attempted values with explicit resolution actions.

Saved draft changes appear for other connected members. Do not silently overwrite a newer edit. Retain the attempted value, show the current saved value and let the user resolve a revision conflict.

Each reviewer selects complete operations or individual stock-row corrections. Selection belongs to that review; it does not change another member's selection. Transfer/Production and all their inputs/outputs form one indivisible selectable entry.

Preview the selection on the server. The preview icon opens or hides it. Show an editable “Changes to submit” spreadsheet, followed by read-only “Resulting stock” for affected rows only. Edit cells directly; shared merged fields update the operation once. Production preview shows consumed lines in red and created lines in green, without separator rows; each group keeps its own add/remove controls. Highlight actual changed cells with their previous values; metadata-only changes leave quantities neutral. Validation errors focus the relevant cell. New pending Stock rows have no history expansion. Refresh preview after selection changes and acknowledged edits. Disable Submit until the current selection has a valid review. Submit accepts exactly that reviewed selection. Changed draft entries or accepted history require another preview. Keep unselected entries pending.

Show the durable acceptance result. Repeated Submit after a lost response must resolve to the original receipt. Validation failure accepts nothing and preserves draft input. Backdated changes that would create negative balances or unavailable production inputs identify the affected records and allow related corrections to be submitted together.

## Realtime and identity

Connected Stock, Operations and draft views receive workspace-scoped notifications after commits. Initial connection and reconnect refresh authoritative state. Users must not need to reload manually to recover a missed notification. Preserve unfinished editor input until its save is acknowledged or a visible conflict is resolved.

Signup creates the first workspace and requires email verification. Support login/logout, password recovery, invitations, joining another workspace and switching workspaces. Confirmation links open a page requiring an explicit confirmation action before consuming the token. Team administration remains manager-only. A user from another workspace cannot obtain stock, draft, operation or history data through UI, direct requests or realtime.

Do not expose provider credentials, physical-unit IDs or technical storage details in the user workflow. Imports, marketplace synchronization, uploads and accounting reports are outside the tester interface.
