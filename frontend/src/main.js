import { createApp } from "vue";
import "../../../mcp-ui/dist/mcp-view.esm.js";
import App from "./App.vue";
const app = createApp(App);
app.config.compilerOptions.isCustomElement = (tag) => tag.startsWith("mcp-");
app.mount("#app");
