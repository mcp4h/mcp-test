const API_BASE = "http://localhost:8080";
async function request(path, options = {}) {
	const response = await fetch(
		`${API_BASE}${path}`,
		{ headers: { "Content-Type": "application/json", ...(options.headers || {}) }, ...options }
	);
	if (!response.ok) {
		const text = await response.text();
		throw new Error(text || response.statusText);
	}
	if (response.status === 204) {
		return null;
	}
	return response.json();
}
export function listServers() {
	return request("/servers");
}
export function createServer(payload) {
	return request("/servers", { method: "POST", body: JSON.stringify(payload) });
}
export function updateServer(serverName, payload) {
	return request(`/servers/${serverName}`, { method: "POST", body: JSON.stringify(payload) });
}
export function startServer(serverName) {
	return request(`/servers/${serverName}/start`, { method: "POST" });
}
export function stopServer(serverName) {
	return request(`/servers/${serverName}/stop`, { method: "POST" });
}
export function getStatus(serverName) {
	return request(`/servers/${serverName}/status`);
}
export function getFacets(serverName) {
	return request(`/servers/${serverName}/facets`);
}
export function invokeTool(serverName, payload) {
	return request(`/servers/${serverName}/invoke`, { method: "POST", body: JSON.stringify(payload) });
}
export function readResource(serverName, payload) {
	return request(`/servers/${serverName}/resource`, { method: "POST", body: JSON.stringify(payload) });
}
export function getPrompt(serverName, payload) {
	return request(`/servers/${serverName}/prompt`, { method: "POST", body: JSON.stringify(payload) });
}
export function listSavedInputs(serverName, toolName) {
	return request(`/servers/${serverName}/tools/${encodeURIComponent(toolName)}/saved`);
}
export function saveInput(serverName, toolName, payload) {
	return request(
		`/servers/${serverName}/tools/${encodeURIComponent(toolName)}/saved`,
		{ method: "POST", body: JSON.stringify(payload) }
	);
}
export function deleteSavedInput(serverName, toolName, savedId) {
	return request(`/servers/${serverName}/tools/${encodeURIComponent(toolName)}/saved/${savedId}`, { method: "DELETE" });
}
export function updateSavedInput(serverName, toolName, savedId, payload) {
	return request(
		`/servers/${serverName}/tools/${encodeURIComponent(toolName)}/saved/${savedId}`,
		{ method: "PUT", body: JSON.stringify(payload) }
	);
}
export function openLogStream(serverName, onMessage) {
	const source = new EventSource(`${API_BASE}/servers/${serverName}/events`);
	source.onmessage = (event) => onMessage(event.data);
	return source;
}
