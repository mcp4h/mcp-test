<template>
	<div class="json-editor" ref="container"></div>
</template>
<script setup>
	import { onBeforeUnmount, onMounted, ref, watch } from "vue";
	import { EditorState } from "@codemirror/state";
	import { EditorView, keymap } from "@codemirror/view";
	import { indentLess, insertTab } from "@codemirror/commands";
	import { json } from "@codemirror/lang-json";
	import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
	import { tags } from "@lezer/highlight";
	const props = defineProps({ modelValue: { type: String, default: "{}" } });
	const emit = defineEmits(["update:modelValue"]);
	const container = ref(null);
	let view;
	const baseTheme = EditorView.theme(
		{
			"&": { backgroundColor: "var(--color-panel-strong)", color: "var(--color-text)", height: "100%" },
			".cm-content": {
				fontFamily: '"IBM Plex Mono", "SFMono-Regular", Menlo, monospace',
				fontSize: "13px",
				lineHeight: "1.5"
			},
			".cm-gutters": { backgroundColor: "var(--color-panel-strong)", color: "var(--color-muted)", border: "none" },
			".cm-activeLine": { backgroundColor: "rgba(249, 115, 22, 0.08)" },
			".cm-cursor": { borderLeftColor: "var(--color-accent-strong)" },
			".cm-selectionBackground": { backgroundColor: "rgba(249, 115, 22, 0.2)" },
			"&.cm-focused .cm-selectionBackground": { backgroundColor: "rgba(249, 115, 22, 0.25)" }
		},
		{ dark: true }
	);
	const customHighlight = HighlightStyle.define(
		[{ tag: tags.string, color: "#fbbf24" }, { tag: tags.number, color: "#6ee7b7" }, { tag: tags.bool, color: "#93c5fd" }, { tag: tags.null, color: "#c4b5fd" }, { tag: tags.propertyName, color: "#fda4af" }, { tag: tags.punctuation, color: "#94a3b8" }]
	);
	const updateListener = EditorView.updateListener
		.of(
			(update) => {
				if (!update.docChanged) return;
				emit("update:modelValue", update.state.doc.toString());
			}
		);
	onMounted(
		() => {
			view = new EditorView(
				{
					parent: container.value,
					state: EditorState.create(
						{
							doc: props.modelValue || "{}",
							extensions: [json(), syntaxHighlighting(customHighlight), keymap.of([{ key: "Tab", run: insertTab }, { key: "Shift-Tab", run: indentLess }]), EditorView.lineWrapping, baseTheme, updateListener]
						}
					)
				}
			);
		}
	);
	onBeforeUnmount(
		() => {
			if (view) view.destroy();
		}
	);
	watch(
		() => props.modelValue,
		(value) => {
			if (!view) return;
			const nextValue = value ?? "";
			const current = view.state.doc.toString();
			if (current === nextValue) return;
			view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: nextValue } });
		}
	);
</script>
<style scoped>
	.json-editor {
		height: 360px;
		border: 1px solid var(--color-border);
		border-radius: 8px;
		overflow: hidden;
		background: var(--color-panel-strong);
	}
</style>
