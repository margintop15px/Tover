# Clojure style

Use [tover.app](src/tover/app.clj) as the layout example. These rules apply to the application.

`Automatic` means cljfmt fixes the rule. `Preserved` means it keeps existing layout; choose the line breaks and blank lines manually.

| Rule | Formatting |
| --- | --- |
| 1. Use two blank lines between functions and major sections; one between related schema definitions. | Preserved. cljfmt cannot insert these separators. |
| 2. Put every `def` value on the next line, including small values. | Preserved. |
| 3. Expand structured schemas vertically, with one `:map` field per line. Keep small schemas compact. | Preserved. |
| 4. Put `:malli/schema` on its own line. Keep a function schema's input signature together and put its return schema below it. | Preserved. |
| 5. Separate function arities with a blank line. Start each arity's body below its argument vector. | Preserved. |
| 6. Align values in multiline maps, independently within nested maps. Keep small maps inline. | Alignment automatic; line breaks preserved. |
| 7. Use minimal spacing between binding names and values. Destructuring must not widen other bindings. | Automatic. Form-column alignment is disabled; excess spaces are removed. |
| 8. Indent bodies by two spaces. Align ordinary call continuation arguments beneath the first argument. | Automatic. `:cursive` argument indentation also gives namespace clauses their four-space entry indent. |
| 9. Keep short nested expressions compact, including small `try`/`catch` forms. | Preserved. |
| 10. Start `:require` and `:import` entries below their clause name, one entry per line. | Indentation automatic; line breaks preserved. |
| 11. Sort namespace references. Import Java classes and use short class names in code. | Sorting automatic in CLI cljfmt; importing classes and replacing names are manual. |
| 12. Attach closing delimiters to the final expression. | Automatic whitespace cleanup. |

## Formatter

[.cljfmt.edn](.cljfmt.edn) configures both CLI cljfmt and Cursive. It preserves blank lines and wrapped values, aligns maps, and disables binding-column alignment. `rdf/with-transaction` has one header argument followed by a body. Bel `defpage`, `defview`, `defaction`, `defstream` and Datastar `->expr` use body indentation.

From this directory:

```sh
clojure -M:fmt check
clojure -M:fmt fix
```

The commands cover `src`, `dev` and `test`. They enforce the automatic rules and preserve manually chosen layout.

IntelliJ uses the shared [Project code style](.idea/codeStyles/Project.xml) with Cursive's cljfmt mode for indentation and **Code → Reformat Code**. Enable **Settings → Tools → Actions on Save → Reformat code** for formatting on save.

The installed Cursive 2026.2 formatter does not implement `:sort-ns-references?` or `:max-column-alignment-gap`. Use the CLI for namespace sorting. The CLI's 20-space alignment-gap limit does not apply in Cursive. Disabling form alignment prevents destructuring padding in both.

See the [cljfmt options](https://github.com/weavejester/cljfmt#formatting-options) and [Cursive formatting guide](https://cursive-ide.com/userguide/formatting.html).
