<template>
	<div class="json-viewer" ref="container"></div>
</template>
<script setup>
	import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
	import { EditorState } from "@codemirror/state";
	import { EditorView } from "@codemirror/view";
	import { json } from "@codemirror/lang-json";
	import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
	import { tags } from "@lezer/highlight";
	const props = defineProps(
		{
			value: { type: [Object, String, null], default: null },
			indentStyle: { type: String, default: "tabs" },
			indentSize: { type: Number, default: 2 }
		}
	);
	const container = ref(null);
	let view;
	const formatted = computed(
		() => {
			if (props.value == null) return "";
			if (typeof props.value === "string") return props.value;
			try {
				const raw = JSON.stringify(props.value, null, props.indentSize || 2);
				if (props.indentStyle !== "tabs") return raw;
				const unit = " ".repeat(props.indentSize || 2);
				const matcher = new RegExp(`^(?:${unit})+`, "gm");
				return raw.replace(matcher, (match) => "\t".repeat(match.length / unit.length));
			}
			catch {
				return String(props.value);
			}
		}
	);
	const baseTheme = EditorView.theme(
		{
			"&": { backgroundColor: "var(--color-panel-strong)", color: "var(--color-text)", height: "100%" },
			".cm-content": {
				fontFamily: '"IBM Plex Mono", "SFMono-Regular", Menlo, monospace',
				fontSize: "13px",
				lineHeight: "1.5"
			},
			".cm-scroller": { overflow: "auto" },
			".cm-gutters": { backgroundColor: "var(--color-panel-strong)", color: "var(--color-muted)", border: "none" },
			".cm-activeLine": { backgroundColor: "transparent" },
			".cm-cursor": { borderLeftColor: "transparent" },
			".cm-selectionBackground": { backgroundColor: "rgba(249, 115, 22, 0.2)" },
			"&.cm-focused .cm-selectionBackground": { backgroundColor: "rgba(249, 115, 22, 0.25)" }
		},
		{ dark: true }
	);
	const customHighlight = HighlightStyle.define(
		[{ tag: tags.string, color: "#fbbf24" }, { tag: tags.number, color: "#6ee7b7" }, { tag: tags.bool, color: "#93c5fd" }, { tag: tags.null, color: "#c4b5fd" }, { tag: tags.propertyName, color: "#fda4af" }, { tag: tags.punctuation, color: "#94a3b8" }]
	);
	onMounted(
		() => {
			view = new EditorView(
				{
					parent: container.value,
					state: EditorState.create(
						{
							doc: formatted.value,
							extensions: [json(), syntaxHighlighting(customHighlight), EditorView.editable.of(false), EditorView.lineWrapping, baseTheme]
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
		formatted,
		(value) => {
			if (!view) return;
			const current = view.state.doc.toString();
			if (current === value) return;
			view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
		}
	);
</script>
<style scoped>
	.json-viewer {
		height: var(--json-viewer-height, 360px);
		min-height: 220px;
		width: 100%;
		border: 1px solid var(--color-border);
		border-radius: 8px;
		overflow: hidden;
		background: var(--color-panel-strong);
	}
</style>
