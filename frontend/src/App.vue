<template>
	<div class="app" :class="{ 'fullscreen-active': isIframeFullscreen }">
		<div class="top-bar">
			<div class="brand">
				<div class="brand-title">MCP Tester</div>
				<div class="brand-sub">MCP testing made simple</div>
			</div>
			<div class="top-bar-right">
				<div v-if="currentServer" class="top-bar-actions">
					<button
						class="toolbar-back"
						@click="activeTab = 'Overview'"
						aria-label="Back to server overview">
						<span>Server Overview</span>
					</button>
				</div>
				<div class="indent-control">
					<span class="indent-label">Indent</span>
					<button
						class="indent-button"
						:class="{ active: indentStyle === 'tabs' }"
						@click="indentStyle = 'tabs'">Tabs</button>
					<button
						class="indent-button"
						:class="{ active: indentStyle === 'spaces' }"
						@click="indentStyle = 'spaces'">Spaces</button>
				</div>
			</div>
		</div>
		<div class="app-shell">
			<main class="main">
				<div v-if="currentServer && activeTab !== 'Overview'" class="section-tabs">
					<div class="section-tabs-group">
						<button
							v-for="tab in tabs"
							:key="tab"
							:class="{ active: activeTab === tab }"
							:disabled="isTabDisabled(tab)"
							@click="activeTab = tab">{{ tab }}</button>
						<div
							v-if="applicationList.length"
							class="section-tabs-separator"
							aria-hidden="true"></div>
						<button
							v-for="app in applicationList"
							:key="app.uri"
							:class="{ active: isApplicationActive(app) }"
							@click="openApplication(app)">{{ app.name || app.uri }}</button>
					</div>
					<div class="section-tabs-status">
						<div class="server-label">
							<span class="server-name">{{ currentServerName }}</span>
							<span class="server-id">{{ currentServer }}</span>
						</div>
						<span class="status-pill" :class="statusClass">{{ statusLabel }}</span>
						<button
							v-if="selectedServer?.transport === 'stdio' && serverStatuses[currentServer]?.running"
							class="ghost"
							@click="restartServer(currentServer)"
							:disabled="connectionState === 'connecting' && currentServer">
							Restart
						</button>
						<button
							v-else-if="selectedServer?.transport !== 'stdio' && serverStatuses[currentServer]?.running"
							class="ghost"
							@click="reconnectServer(currentServer)"
							:disabled="connectionState === 'connecting' && currentServer">
							Reconnect
						</button>
					</div>
				</div>
				<section v-if="!currentServer || activeTab === 'Overview'" class="panel">
					<div class="overview-grid">
						<button class="card add-card" @click="showAddModal = true">
							<div class="add-card-content">
								<span class="add-icon">+</span>
								<div>
									<h3>Add Server</h3>
									<p class="muted">Create a new MCP connection profile.</p>
								</div>
							</div>
						</button>
						<div
							v-for="server in servers"
							:key="server.id"
							class="card server-card"
							:class="{ active: server.id === currentServer }">
							<div class="server-card-header">
								<div>
									<h3>{{ server.name }}</h3>
									<div class="muted" v-if="server.description">{{ server.description }}</div>
								</div>
								<span class="status" :class="serverStatuses[server.id]?.running ? 'running' : 'stopped'">{{ serverStatuses[server.id]?.running ? 'Connected' : 'Disconnected' }}</span>
							</div>
							<div class="tag-row">
								<span class="meta-chip">{{ server.transport === 'sse' ? 'SSE' : (server.transport === 'streamable' ? 'Streamable HTTP' : 'Stdio') }}</span>
								<span v-if="server.supportsTools" class="tag supported">Tools</span>
								<span v-if="server.supportsResources" class="tag supported">Resources</span>
								<span v-if="server.supportsPrompts" class="tag supported">Prompts</span>
							</div>
							<div class="button-row">
								<button
									@click="viewServer(server.id)"
									:disabled="connectionState === 'connecting' && currentServer === server.id">
									View
								</button>
								<button
									v-if="server.transport === 'stdio' && serverStatuses[server.id]?.running"
									class="ghost"
									@click="restartServer(server.id)"
									:disabled="connectionState === 'connecting' && currentServer === server.id">
									Restart
								</button>
								<button
									v-else-if="server.transport !== 'stdio' && serverStatuses[server.id]?.running"
									class="ghost"
									@click="reconnectServer(server.id)"
									:disabled="connectionState === 'connecting' && currentServer === server.id">
									Reconnect
								</button>
								<button class="ghost" @click="startEdit(server)">Edit</button>
							</div>
						</div>
					</div>
				</section>
				<section v-else-if="!currentServer" class="panel">
					<div class="card empty-state">
						<h2>Select a server</h2>
						<p class="muted">Open a server from the overview to explore tools, resources, and prompts.</p>
					</div>
				</section>
				<section v-else-if="connectionState === 'connecting'" class="panel">
					<div class="card empty-state">
						<h2>Connecting...</h2>
						<p class="muted">Starting or connecting to {{ currentServerName }}.</p>
					</div>
				</section>
				<section v-else-if="!status.running && activeTab !== 'Raw Log'" class="panel">
					<div class="card empty-state disconnected">
						<div class="plug-icon" aria-hidden="true">
							<svg
								viewBox="0 0 64 64"
								role="img"
								focusable="false">
								<path
									d="M24 8v16m16-16v16m-24 8h32v10a12 12 0 0 1-12 12H28a12 12 0 0 1-12-12V32z"
									fill="none"
									stroke="currentColor"
									stroke-width="3"
									stroke-linecap="round"
									stroke-linejoin="round"/>
								<path
									d="M18 32h28"
									fill="none"
									stroke="currentColor"
									stroke-width="3"
									stroke-linecap="round"/>
							</svg>
						</div>
						<h2>Could not connect</h2>
						<p class="muted">{{ connectionError || "The server did not respond. Check the logs below." }}</p>
						<div v-if="recentLogLines.length" class="log-preview">
							<pre class="log">{{ recentLogLines.join("\n") }}</pre>
						</div>
						<div class="button-row">
							<button @click="viewServer(currentServer)">Try again</button>
							<button class="ghost" @click="activeTab = 'Raw Log'">View Raw Log</button>
						</div>
					</div>
				</section>
				<section v-else-if="activeTab === 'Definition'" class="panel definition-panel">
					<div class="card definition-card">
						<div class="panel-header">
							<h2>Server Definition</h2>
							<button
								class="refresh-button"
								@click="refreshDefinition"
								:disabled="!currentServer">Refresh</button>
						</div>
						<div class="definition-controls">
							<label class="toggle-button" :class="{ active: showTools, disabled: !status.running }">
								<input
									type="checkbox"
									v-model="showTools"
									:disabled="!status.running"/>
								Tools
							</label>
							<label class="toggle-button" :class="{ active: showResources, disabled: !status.running }">
								<input
									type="checkbox"
									v-model="showResources"
									:disabled="!status.running"/>
								Resources
							</label>
							<label class="toggle-button" :class="{ active: showPrompts, disabled: !status.running }">
								<input
									type="checkbox"
									v-model="showPrompts"
									:disabled="!status.running"/>
								Prompts
							</label>
						</div>
						<div class="definition-viewer">
							<JsonViewer
								v-if="serverDefinition"
								:value="serverDefinition"
								:indent-style="indentStyle"
								:indent-size="indentSize"/>
							<div v-else class="muted">Server definition unavailable.</div>
						</div>
					</div>
				</section>
				<section v-else-if="activeTab === 'Application'" class="panel application-panel">
					<div class="card application-card">
						<div class="panel-header">
							<h2>{{ selectedApplication?.name || "Application" }}</h2>
						</div>
						<div
							v-if="selectedApplication"
							:class="['content-selected-toolbar', { fullscreen: isIframeFullscreen }]">
							<button class="ghost" @click="toggleIframeFullscreen">{{ isIframeFullscreen ? "Leave Full Screen" : "Full Screen" }}</button>
						</div>
						<div v-if="!selectedApplication" class="muted">Select an application to launch.</div>
						<div v-else class="content-selected">
							<div ref="applicationContainer" class="application-container">
								<mcp-view
									:class="['content-mcp-view', { fullscreen: isIframeFullscreen }]"
									:src="selectedApplication.uri"></mcp-view>
							</div>
						</div>
					</div>
				</section>
				<section v-else-if="activeTab === 'Tools'" class="panel">
					<div class="split">
						<div class="card">
							<div class="panel-header">
								<h2>Tools</h2>
								<button
									class="refresh-button"
									@click="loadFacets"
									:disabled="!status.running">Refresh</button>
							</div>
							<div v-if="currentServer && !selectedServer?.supportsTools" class="banner">
								Not supported by this server.
							</div>
							<ul class="list compact">
								<li
									v-for="tool in toolList"
									:key="tool.name"
									:class="{ selected: tool.name === selectedTool }"
									@click="selectTool(tool)">
									<div>
										<strong>{{ tool.name }}</strong>
										<div class="muted">{{ tool.description || '' }}</div>
									</div>
								</li>
							</ul>
						</div>
						<div class="card">
							<h2>Tool</h2>
							<div v-if="selectedTool && selectedServer?.supportsTools">
								<div class="subtabs">
									<button :class="{ active: toolPanelTab === 'invoke' }" @click="toolPanelTab = 'invoke'">Invoke</button>
									<button :class="{ active: toolPanelTab === 'input-schema' }" @click="toolPanelTab = 'input-schema'">Input Schema</button>
									<button
										:class="{ active: toolPanelTab === 'output-schema' }"
										@click="toolPanelTab = 'output-schema'">Output Schema</button>
									<button :class="{ active: toolPanelTab === 'meta' }" @click="toolPanelTab = 'meta'">Meta</button>
								</div>
								<div v-if="toolPanelTab === 'invoke'">
									<div class="field-row">
										<label>JSON input</label>
										<div class="field-row-actions">
											<button class="ghost" @click="addMeta">Add meta key</button>
											<button
												class="ghost"
												@click="generateTemplate"
												:disabled="!getToolInputSchema(selectedToolInfo)">Generate Input</button>
											<button class="ghost" @click="formatToolJson">Format Input</button>
											<button class="ghost" @click="openSavedInputs">Load Input</button>
											<button class="ghost" @click="openSaveInputs">Save Input</button>
										</div>
									</div>
									<JsonEditorAdapter v-model="toolJson"/>
									<div v-if="toolJsonError" class="banner error">{{ toolJsonError }}</div>
									<div class="meta">
										<div
											v-for="(entry, index) in metaEntries"
											:key="index"
											class="meta-row">
											<input v-model="entry.key" placeholder="key"/>
											<input v-model="entry.value" placeholder="value"/>
											<button class="ghost" @click="removeMeta(index)">Remove</button>
										</div>
									</div>
									<div class="button-row">
										<button @click="invokeSelected">Invoke</button>
									</div>
									<div class="panel-header">
										<h3>Response</h3>
										<div class="response-tabs">
											<button :class="{ active: toolResponseTab === 'result' }" @click="toolResponseTab = 'result'">Result</button>
											<button
												v-for="tab in responseContentTypes"
												:key="tab"
												:class="{ active: toolResponseTab === tab }"
												@click="toolResponseTab = tab">{{ tab }}</button>
											<button :class="{ active: toolResponseTab === 'meta' }" @click="toolResponseTab = 'meta'">Meta</button>
										</div>
									</div>
									<div ref="toolResponseContainer" class="tool-response">
										<div v-if="toolResponseTab === 'result'">
											<JsonViewer
												:value="responseResult"
												:indent-style="indentStyle"
												:indent-size="indentSize"/>
										</div>
										<div v-else-if="toolResponseTab === 'meta'" class="schema">
											<JsonViewer
												v-if="responseMeta"
												:value="responseMeta"
												:indent-style="indentStyle"
												:indent-size="indentSize"/>
											<div v-else class="muted">No _meta returned in the response.</div>
										</div>
										<div v-else class="content-panel">
											<div v-if="!contentEntriesByType(toolResponseTab).length" class="muted">No content entries for this type.</div>
											<div v-else class="content-entry">
												<div v-if="activeContentEntry(toolResponseTab)" class="content-selected">
													<div
														v-if="shouldRenderMcpView(activeContentEntry(toolResponseTab))"
														:class="['content-selected-toolbar', { fullscreen: isIframeFullscreen }]">
														<button class="ghost" @click="toggleIframeFullscreen">{{ isIframeFullscreen ? "Leave Full Screen" : "Full Screen" }}</button>
													</div>
													<div v-if="isContentLoading(toolResponseTab)" class="muted">Loading resource...</div>
													<mcp-view
														v-show="shouldShowIframeForTab(toolResponseTab)"
														:class="['content-mcp-view', { fullscreen: isIframeFullscreen }]"
														:src="activeContentEntry(toolResponseTab)?.url || ''"></mcp-view>
													<pre
														v-if="!shouldShowIframeForTab(toolResponseTab) && isDiffEntry(activeContentEntry(toolResponseTab))"
														class="content-diff">
														<span
															v-for="(line, lineIndex) in diffLines(activeContentText(toolResponseTab))"
															:key="lineIndex"
															:class="line.className">{{ line.text }}</span>
													</pre>
													<pre
														v-else-if="!shouldShowIframeForTab(toolResponseTab) && activeContentText(toolResponseTab)"
														class="content-text">{{ activeContentText(toolResponseTab) }}</pre>
													<JsonViewer
														v-else-if="!shouldShowIframeForTab(toolResponseTab)"
														:value="activeContentEntry(toolResponseTab)"
														:indent-style="indentStyle"
														:indent-size="indentSize"/>
												</div>
												<div v-if="contentEntriesWithoutUrl(toolResponseTab).length" class="content-text-list">
													<div
														v-for="(textEntry, textIndex) in contentEntriesWithoutUrl(toolResponseTab)"
														:key="entryKey(textEntry, textIndex)"
														class="content-entry">
														<pre v-if="textEntry.text" class="content-text">{{ textEntry.text }}</pre>
														<JsonViewer
															v-else
															:value="textEntry"
															:indent-style="indentStyle"
															:indent-size="indentSize"/>
													</div>
												</div>
											</div>
										</div>
									</div>
								</div>
								<div v-else-if="toolPanelTab === 'input-schema'" class="schema">
									<JsonViewer
										v-if="getToolInputSchema(selectedToolInfo)"
										:value="getToolInputSchema(selectedToolInfo)"
										:indent-style="indentStyle"
										:indent-size="indentSize"/>
									<div v-else class="muted">No input schema available.</div>
								</div>
								<div v-else-if="toolPanelTab === 'output-schema'" class="schema">
									<JsonViewer
										v-if="getToolOutputSchema(selectedToolInfo)"
										:value="getToolOutputSchema(selectedToolInfo)"
										:indent-style="indentStyle"
										:indent-size="indentSize"/>
									<div v-else class="muted">No output schema available.</div>
								</div>
								<div v-else-if="toolPanelTab === 'meta'" class="schema">
									<JsonViewer
										v-if="toolMeta"
										:value="toolMeta"
										:indent-style="indentStyle"
										:indent-size="indentSize"/>
									<div v-else class="muted">No _meta reported for this tool.</div>
								</div>
							</div>
							<div v-else-if="!selectedServer?.supportsTools" class="muted">Tools are not supported by this server.</div>
							<div v-else class="muted">Select a tool to start.</div>
						</div>
					</div>
				</section>
				<section v-else-if="activeTab === 'Resources'" class="panel">
					<div class="split">
						<div class="card">
							<div class="panel-header">
								<h2>Resources</h2>
								<button
									class="refresh-button"
									@click="loadFacets"
									:disabled="!status.running">Refresh</button>
							</div>
							<div v-if="currentServer && !selectedServer?.supportsResources" class="banner">
								Not supported by this server.
							</div>
							<ul class="list compact">
								<li
									v-for="res in resourceList"
									:key="res.uri"
									:class="{ selected: res.uri === selectedResource?.uri }"
									@click="selectResource(res)">
									<div>
										<strong>{{ res.name || res.uri }}</strong>
										<div class="muted">{{ res.uri }}</div>
									</div>
								</li>
							</ul>
						</div>
						<div class="card">
							<h2>Read Resource</h2>
							<div v-if="selectedResource && selectedServer?.supportsResources">
								<div class="muted">{{ selectedResource.uri }}</div>
								<div v-if="resourceVars.length" class="meta">
									<div class="field-row">
										<h3>URI Variables</h3>
									</div>
									<div
										v-for="variable in resourceVars"
										:key="variable"
										class="meta-row">
										<input v-model="resourceValues[variable]" :placeholder="variable"/>
									</div>
								</div>
								<div class="button-row">
									<button @click="readSelectedResource">Read</button>
								</div>
								<h3>Response</h3>
								<JsonViewer
									:value="resourceResponse"
									:indent-style="indentStyle"
									:indent-size="indentSize"/>
							</div>
							<div v-else-if="!selectedServer?.supportsResources" class="muted">Resources are not supported by this server.</div>
							<div v-else class="muted">Select a resource to start.</div>
						</div>
					</div>
				</section>
				<section v-else-if="activeTab === 'Prompts'" class="panel">
					<div class="split">
						<div class="card">
							<div class="panel-header">
								<h2>Prompts</h2>
								<button
									class="refresh-button"
									@click="loadFacets"
									:disabled="!status.running">Refresh</button>
							</div>
							<div v-if="currentServer && !selectedServer?.supportsPrompts" class="banner">
								Not supported by this server.
							</div>
							<ul class="list compact">
								<li
									v-for="prompt in promptList"
									:key="prompt.name"
									:class="{ selected: prompt.name === selectedPrompt?.name }"
									@click="selectPrompt(prompt)">
									<div>
										<strong>{{ prompt.name }}</strong>
										<div class="muted">{{ prompt.description || '' }}</div>
									</div>
								</li>
							</ul>
						</div>
						<div class="card">
							<h2>Get Prompt</h2>
							<div v-if="selectedPrompt && selectedServer?.supportsPrompts">
								<div class="field-row">
									<label>Arguments</label>
									<select v-model="promptMode">
										<option value="code">Raw</option>
										<option value="tree">Tree</option>
										<option value="form">Form</option>
										<option value="view">View</option>
									</select>
								</div>
								<JsonEditorAdapter v-model="promptJson" :mode="promptMode"/>
								<div v-if="promptJsonError" class="banner error">{{ promptJsonError }}</div>
								<div class="button-row">
									<button @click="getSelectedPrompt">Get Prompt</button>
								</div>
								<h3>Response</h3>
								<JsonViewer
									:value="promptResponse"
									:indent-style="indentStyle"
									:indent-size="indentSize"/>
							</div>
							<div v-else-if="!selectedServer?.supportsPrompts" class="muted">Prompts are not supported by this server.</div>
							<div v-else class="muted">Select a prompt to start.</div>
						</div>
					</div>
				</section>
				<section v-else-if="activeTab === 'Raw Log'" class="panel">
					<div class="card">
						<div v-if="!status.running" class="disconnect-banner">
							<div class="plug-icon" aria-hidden="true">
								<svg
									viewBox="0 0 64 64"
									role="img"
									focusable="false">
									<path
										d="M24 8v16m16-16v16m-24 8h32v10a12 12 0 0 1-12 12H28a12 12 0 0 1-12-12V32z"
										fill="none"
										stroke="currentColor"
										stroke-width="3"
										stroke-linecap="round"
										stroke-linejoin="round"/>
									<path
										d="M18 32h28"
										fill="none"
										stroke="currentColor"
										stroke-width="3"
										stroke-linecap="round"/>
								</svg>
							</div>
							<div>
								<strong>Disconnected</strong>
								<div class="muted">We could not connect to this server.</div>
							</div>
						</div>
						<h2>Live Log</h2>
						<div class="button-row">
							<button @click="clearLog">Clear</button>
						</div>
						<div class="log-filters">
							<button :class="{ active: logFilter === 'json' }" @click="logFilter = 'json'">JSON</button>
							<button :class="{ active: logFilter === 'all' }" @click="logFilter = 'all'">All</button>
							<button :class="{ active: logFilter === 'text' }" @click="logFilter = 'text'">Text</button>
						</div>
						<div class="log-split">
							<div class="log-list">
								<div
									v-for="(entry, index) in filteredLogEntries"
									:key="index"
									class="log-entry"
									:class="{ selected: selectedLogLine === entry.line }"
									@click="selectLogEntry(entry)">
									<span class="log-text">{{ entry.displayLine }}</span>
									<span v-if="entry.isJson" class="log-chip">JSON</span>
								</div>
								<div v-if="!filteredLogEntries.length" class="muted">No log entries yet.</div>
							</div>
							<aside class="log-detail">
								<div class="panel-header">
									<h3>Log Detail</h3>
									<button
										v-if="selectedLogJson"
										class="ghost"
										@click="copySelectedJson">Copy JSON</button>
								</div>
								<div v-if="selectedLogJson" class="log-detail-json">
									<JsonViewer
										:value="selectedLogJson"
										:indent-style="indentStyle"
										:indent-size="indentSize"/>
								</div>
								<pre v-else class="log-detail-raw">{{ selectedLogLine || "Select a log entry to inspect." }}</pre>
							</aside>
						</div>
					</div>
				</section>
			</main>
		</div>
		<div
			v-if="showAddModal"
			class="modal-overlay"
			@click.self="showAddModal = false">
			<div class="modal">
				<div class="modal-header">
					<h2>Add Server</h2>
					<button
						class="icon-button"
						@click="showAddModal = false"
						aria-label="Close">
						<svg
							viewBox="0 0 24 24"
							role="img"
							focusable="false">
							<path
								d="M6 6l12 12M18 6l-12 12"
								fill="none"
								stroke="currentColor"
								stroke-width="2"
								stroke-linecap="round"
								stroke-linejoin="round"/>
						</svg>
					</button>
				</div>
				<div class="grid">
					<label>
						Name
						<input v-model="newServer.name" placeholder="my-mcp"/>
					</label>
					<label>
						Description (optional)
						<input v-model="newServer.description" placeholder="short summary"/>
					</label>
					<label>
						Transport
						<select v-model="newServer.transport">
							<option value="stdio">Stdio</option>
							<option value="sse">SSE</option>
							<option value="streamable">Streamable HTTP</option>
						</select>
					</label>
					<label v-if="newServer.transport === 'sse' || newServer.transport === 'streamable'">
						Endpoint URL
						<input v-model="newServer.httpUrl" placeholder="https://host/mcp"/>
					</label>
					<label v-if="newServer.transport === 'sse'">
						Message URL (optional)
						<input v-model="newServer.httpMessageUrl" placeholder="https://host/message"/>
					</label>
					<label v-if="newServer.transport === 'sse' || newServer.transport === 'streamable'">
						HTTP Headers (KEY=VALUE per line)
						<textarea
							v-model="newServer.httpHeaders"
							rows="4"
							placeholder="Header=Value"></textarea>
					</label>
					<label v-if="newServer.transport === 'stdio'">
						Command
						<input v-model="newServer.command" placeholder="node server.js --flag"/>
					</label>
					<label v-if="newServer.transport === 'stdio'">
						CWD (optional)
						<input v-model="newServer.cwd" placeholder="/path/to/server"/>
					</label>
					<label v-if="newServer.transport === 'stdio'">
						Framing
						<select v-model="newServer.framing">
							<option value="ndjson">NDJSON</option>
							<option value="content-length">Content-Length</option>
						</select>
					</label>
					<label>
						Env (KEY=VALUE per line)
						<textarea
							v-model="newServer.env"
							rows="4"
							placeholder="FOO=bar"></textarea>
					</label>
				</div>
				<div class="button-row">
					<button @click="createNewServer">Save Server</button>
					<button class="ghost" @click="showAddModal = false">Cancel</button>
				</div>
			</div>
		</div>
		<div
			v-if="showSavedInputs"
			class="sidepane-overlay"
			@click.self="showSavedInputs = false">
			<aside
				class="sidepane"
				role="dialog"
				aria-label="Saved inputs">
				<div class="sidepane-header">
					<div>
						<h3>Saved Inputs</h3>
						<div class="muted">Store and reuse tool payloads.</div>
					</div>
					<button
						class="icon-button"
						@click="showSavedInputs = false"
						aria-label="Close">
						<svg
							viewBox="0 0 24 24"
							role="img"
							focusable="false">
							<path
								d="M6 6l12 12M18 6l-12 12"
								fill="none"
								stroke="currentColor"
								stroke-width="2"
								stroke-linecap="round"
								stroke-linejoin="round"/>
						</svg>
					</button>
				</div>
				<div class="sidepane-body">
					<div class="saved">
						<div v-if="savedInputsNotice" class="banner notice">{{ savedInputsNotice }}</div>
						<div v-if="savedInputsError" class="banner error">{{ savedInputsError }}</div>
						<div v-if="!savedInputs.length" class="empty-state">
							<h3>No saved inputs</h3>
							<p class="muted">Save the current JSON input to reuse it later.</p>
						</div>
						<ul v-else class="list compact saved-list">
							<li
								v-for="input in savedInputs"
								:key="input.id"
								:class="{ selected: input.id === selectedSavedInputId }">
								<div>
									<strong>{{ input.name }}</strong>
									<div class="muted">{{ input.comment || '' }}</div>
								</div>
								<div class="button-row compact">
									<button class="ghost" @click="loadSavedInput(input)">Load</button>
									<button @click="deleteSaved(input)">Delete</button>
								</div>
							</li>
						</ul>
					</div>
				</div>
			</aside>
		</div>
		<div
			v-if="showSaveInputs"
			class="sidepane-overlay"
			@click.self="closeSaveInputs">
			<aside
				class="sidepane"
				role="dialog"
				aria-label="Save input">
				<div class="sidepane-header">
					<div>
						<h3>Save Input</h3>
						<div class="muted">Save a new input or overwrite an existing one.</div>
					</div>
					<button
						class="icon-button"
						@click="closeSaveInputs"
						aria-label="Close">
						<svg
							viewBox="0 0 24 24"
							role="img"
							focusable="false">
							<path
								d="M6 6l12 12M18 6l-12 12"
								fill="none"
								stroke="currentColor"
								stroke-width="2"
								stroke-linecap="round"
								stroke-linejoin="round"/>
						</svg>
					</button>
				</div>
				<div class="sidepane-body">
					<div class="saved">
						<div v-if="savedInputsNotice" class="banner notice">{{ savedInputsNotice }}</div>
						<div v-if="savedInputsError" class="banner error">{{ savedInputsError }}</div>
						<div class="card saved-new-card">
							<div>
								<strong>New Input</strong>
								<div class="muted">Create a new saved input.</div>
							</div>
							<div class="grid">
								<label>
									Title
									<input v-model="saveInputDraft.name" placeholder="input title"/>
								</label>
								<label>
									Description (optional)
									<input v-model="saveInputDraft.comment" placeholder="short note"/>
								</label>
							</div>
							<div class="button-row">
								<button @click="saveNewInputFromSidebar" :disabled="!saveInputDraft.name">Save as New</button>
							</div>
						</div>
						<div v-if="!savedInputs.length" class="empty-state">
							<h3>No saved inputs</h3>
							<p class="muted">Use the form above to save your first input.</p>
						</div>
						<ul v-else class="list compact saved-list">
							<li
								v-for="input in savedInputs"
								:key="input.id">
								<div>
									<strong>{{ input.name }}</strong>
									<div class="muted">{{ input.comment || '' }}</div>
								</div>
								<div class="button-row compact">
									<button @click="overwriteSavedInput(input)">Overwrite</button>
									<button class="ghost" @click="deleteSaved(input)">Delete</button>
								</div>
							</li>
						</ul>
					</div>
				</div>
			</aside>
		</div>
		<div
			v-if="showEditModal"
			class="modal-overlay"
			@click.self="cancelEdit">
			<div class="modal">
				<div class="modal-header">
					<h2>Edit Server</h2>
					<button
						class="icon-button"
						@click="cancelEdit"
						aria-label="Close">
						<svg
							viewBox="0 0 24 24"
							role="img"
							focusable="false">
							<path
								d="M6 6l12 12M18 6l-12 12"
								fill="none"
								stroke="currentColor"
								stroke-width="2"
								stroke-linecap="round"
								stroke-linejoin="round"/>
						</svg>
					</button>
				</div>
				<div class="grid">
					<label>
						Name
						<input v-model="editServerForm.name"/>
					</label>
					<label>
						Description
						<input v-model="editServerForm.description"/>
					</label>
					<label>
						Transport
						<select v-model="editServerForm.transport">
							<option value="stdio">Stdio</option>
							<option value="sse">SSE</option>
							<option value="streamable">Streamable HTTP</option>
						</select>
					</label>
					<label v-if="editServerForm.transport === 'sse' || editServerForm.transport === 'streamable'">
						Endpoint URL
						<input v-model="editServerForm.httpUrl"/>
					</label>
					<label v-if="editServerForm.transport === 'sse'">
						Message URL (optional)
						<input v-model="editServerForm.httpMessageUrl"/>
					</label>
					<label v-if="editServerForm.transport === 'sse' || editServerForm.transport === 'streamable'">
						HTTP Headers (KEY=VALUE per line)
						<textarea v-model="editServerForm.httpHeaders" rows="4"></textarea>
					</label>
					<label v-if="editServerForm.transport === 'stdio'">
						Command
						<input v-model="editServerForm.command"/>
					</label>
					<label v-if="editServerForm.transport === 'stdio'">
						CWD
						<input v-model="editServerForm.cwd"/>
					</label>
					<label v-if="editServerForm.transport === 'stdio'">
						Framing
						<select v-model="editServerForm.framing">
							<option value="ndjson">NDJSON</option>
							<option value="content-length">Content-Length</option>
						</select>
					</label>
					<label>
						Env (KEY=VALUE per line)
						<textarea v-model="editServerForm.env" rows="4"></textarea>
					</label>
				</div>
				<p class="muted">Changing the name renames the server id. Stop the server before renaming.</p>
				<div class="button-row">
					<button @click="saveServerEdits(editServerId)">Save</button>
					<button class="ghost" @click="cancelEdit">Cancel</button>
				</div>
			</div>
		</div>
	</div>
</template>
<script setup>
	import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
	import JsonEditorAdapter from "./components/JsonEditorAdapter.vue";
	import JsonViewer from "./components/JsonViewer.vue";
	import {
		createServer,
		deleteSavedInput,
		getFacets,
		getPrompt,
		getStatus,
		invokeTool,
		listSavedInputs,
		listServers,
		openLogStream,
		readResource,
		saveInput,
		startServer,
		stopServer,
		updateSavedInput,
		updateServer
	} from "./api";
	const tabs = ["Tools", "Resources", "Prompts", "Raw Log", "Definition"];
	const activeTab = ref("Overview");
	const servers = ref([]);
	const currentServer = ref("");
	const status = ref({ running: false, capabilities: null, initialize: null });
	const connectionState = ref("idle");
	const connectionError = ref("");
	const serverStatuses = ref({});
	const facets = ref({
		tools: null,
		resources: null,
		prompts: null,
		applications: null
	});
	const selectedTool = ref("");
	const selectedToolInfo = ref(null);
	const toolJson = ref("{}");
	const toolResponse = ref(null);
	const toolResponseTab = ref("result");
	const toolResponseContainer = ref(null);
	const applicationContainer = ref(null);
	const activeContentPayloads = ref({});
	const activeContentLoading = ref({});
	const isIframeFullscreen = ref(false);
	const toolJsonError = ref("");
	const metaEntries = ref([]);
	const savedInputs = ref([]);
	const selectedSavedInputId = ref("");
	const editServerId = ref("");
	const editServerForm = ref(
		{
			name: "",
			description: "",
			command: "",
			cwd: "",
			framing: "ndjson",
			transport: "stdio",
			httpUrl: "",
			httpMessageUrl: "",
			httpHeaders: "",
			env: ""
		}
	);
	const selectedResource = ref(null);
	const resourceResponse = ref(null);
	const resourceValues = ref({});
	const selectedApplication = ref(null);
	const selectedPrompt = ref(null);
	const promptJson = ref("{}");
	const promptResponse = ref(null);
	const promptMode = ref("code");
	const promptJsonError = ref("");
	const logLines = ref([]);
	const toolPanelTab = ref("invoke");
	const showAddModal = ref(false);
	const showEditModal = ref(false);
	const showSavedInputs = ref(false);
	const showSaveInputs = ref(false);
	const saveInputDraft = ref({ name: "", comment: "" });
	const savedInputsNotice = ref("");
	const savedInputsError = ref("");
	const currentLoadedSavedId = ref("");
	const currentLoadedInputMeta = ref({ name: "", comment: "" });
	const indentStyle = ref("tabs");
	const indentSize = ref(2);
	const newServer = ref(
		{
			name: "",
			description: "",
			command: "",
			cwd: "",
			framing: "ndjson",
			transport: "stdio",
			httpUrl: "",
			httpMessageUrl: "",
			httpHeaders: "",
			env: ""
		}
	);
	let logStream;
	const toolList = computed(() => facets.value.tools?.tools || []);
	const applicationList = computed(
		() => {
			if (Array.isArray(facets.value.applications)) return facets.value.applications;
			const resources = facets.value.resources?.resources;
			if (!Array.isArray(resources)) return [];
			return resources.filter((entry) => entry?.annotations?.type === "application" && entry?.mimeType?.startsWith("text/html"))
				.map((entry) => ({ name: entry?.name, uri: entry?.uri }))
				.filter((entry) => entry?.uri);
		}
	);
	const toolMeta = computed(
		() => {
			if (!selectedToolInfo.value) return null;
			return selectedToolInfo.value._meta || selectedToolInfo.value.meta || null;
		}
	);
	const responseResult = computed(
		() => {
			if (!toolResponse.value) return null;
			if (toolResponse.value.result !== undefined) return toolResponse.value.result;
			return toolResponse.value;
		}
	);
	const responseMeta = computed(
		() => {
			if (!toolResponse.value || typeof toolResponse.value !== "object") return null;
			return toolResponse.value._meta || toolResponse.value.meta || null;
		}
	);
	const responseContentEntries = computed(
		() => {
			const result = responseResult.value;
			if (!result || typeof result !== "object") return [];
			const contents = result.content ?? result.contents ?? [];
			if (!Array.isArray(contents)) return [];
			return contents.filter((entry) => entry && typeof entry === "object");
		}
	);
	const responseContentTypes = computed(
		() => {
			const types = new Set();
			for (const entry of responseContentEntries.value) {
				if (typeof entry.type === "string" && entry.type.trim()) {
					types.add(entry.type);
				}
			}
			return Array.from(types);
		}
	);
	function contentEntriesByType(type) {
		return responseContentEntries.value.filter((entry) => entry.type === type);
	}
	function contentEntriesWithUrl(type) {
		return contentEntriesByType(type).filter((entry) => hasContentUrl(entry));
	}
	function contentEntriesWithoutUrl(type) {
		return contentEntriesByType(type).filter((entry) => !hasContentUrl(entry));
	}
	function hasContentUrl(entry) {
		return typeof entry?.url === "string" && entry.url.length > 0;
	}
	function isUiContent(entry) {
		const url = entry?.url;
		return typeof url === "string" && url.startsWith("ui://");
	}
	function effectiveMimeType(entry) {
		if (entry?.mimeType && typeof entry.mimeType === "string") return entry.mimeType;
		if (entry?.type === "review") return "text/html";
		if (entry?.type === "diff") return "text/x-diff";
		return "";
	}
	function shouldRenderMcpView(entry) {
		if (!isUiContent(entry)) return false;
		const mimeType = effectiveMimeType(entry);
		if (mimeType) return mimeType === "text/html";
		return false;
	}
	function isDiffEntry(entry) {
		const mimeType = effectiveMimeType(entry);
		return mimeType === "text/x-diff";
	}
	function diffLines(text) {
		if (!text) return [];
		return text.split("\n")
			.map(
				(line) => {
					let className = "diff-line";
					if (line.startsWith("+")) className += " diff-added";
					else if (line.startsWith("-")) className += " diff-removed";
					else if (line.startsWith("@@")) className += " diff-hunk";
					return { text: line, className };
				}
			);
	}
	function entryKey(entry, index) {
		const url = entry?.url;
		if (typeof url === "string" && url) return `${entry.type || "content"}:${url}`;
		const text = entry?.text;
		if (typeof text === "string" && text) return `${entry.type || "content"}:${text.slice(0, 24)}`;
		return `${entry?.type || "content"}:${index}`;
	}
	function activeContentEntry(type) {
		const entries = contentEntriesWithUrl(type);
		return entries.length ? entries[0] : null;
	}
	function activeContentKey(type) {
		const entry = activeContentEntry(type);
		if (!entry) return "";
		return entryKey(entry, 0);
	}
	function isContentLoading(type) {
		const key = activeContentKey(type);
		return key ? Boolean(activeContentLoading.value[key]) : false;
	}
	function normalizeContentText(value) {
		if (typeof value === "string") return value;
		if (value instanceof Uint8Array) return new TextDecoder().decode(value);
		if (value instanceof ArrayBuffer) return new TextDecoder().decode(new Uint8Array(value));
		return value == null ? "" : JSON.stringify(value, null, 2);
	}
	function activeContentText(type) {
		const key = activeContentKey(type);
		if (!key) return "";
		return normalizeContentText(activeContentPayloads.value[key]);
	}
	function toggleIframeFullscreen() {
		isIframeFullscreen.value = !isIframeFullscreen.value;
	}
	function closeIframeFullscreen() {
		isIframeFullscreen.value = false;
	}
	async function loadContentForType(type) {
		const entry = activeContentEntry(type);
		if (!entry || !hasContentUrl(entry)) return;
		const key = activeContentKey(type);
		if (!key || activeContentPayloads.value[key]) return;
		console.info("[mcp-test] load content for tab", { type, url: entry.url });
		activeContentLoading.value = { ...activeContentLoading.value, [key]: true };
		const payload = await resolveUiResource(entry.url);
		activeContentPayloads.value = { ...activeContentPayloads.value, [key]: payload };
		activeContentLoading.value = { ...activeContentLoading.value, [key]: false };
	}
	function decodeBase64ToUint8Array(value) {
		const binary = atob(value);
		const bytes = new Uint8Array(binary.length);
		for (let i = 0; i < binary.length; i += 1) {
			bytes[i] = binary.charCodeAt(i);
		}
		return bytes;
	}
	async function resolveUiResource(uri) {
		if (!currentServer.value) return "No server selected.";
		try {
			console.info("[mcp-test] resolve ui resource", { uri, server: currentServer.value });
			const response = await readResource(currentServer.value, { uri });
			console.info("[mcp-test] resource response", { uri, response });
			if (typeof response === "string") return response;
			const contents = response?.contents ?? response?.content ?? [];
			if (Array.isArray(contents) && contents.length) {
				const first = contents.find((entry) => entry && typeof entry === "object") || contents[0];
				console.info("[mcp-test] resource entry", { uri, entry: first });
				if (typeof first?.text === "string") return first.text;
				if (typeof first?.base64 === "string") return decodeBase64ToUint8Array(first.base64);
				if (typeof first?.bytes === "string") return decodeBase64ToUint8Array(first.bytes);
				if (typeof first?.data === "string") return decodeBase64ToUint8Array(first.data);
			}
			return JSON.stringify(response ?? "", null, 2);
		}
		catch (error) {
			console.error("[mcp-test] resolve ui resource failed", { uri, error });
			return error?.message || "Failed to resolve ui resource.";
		}
	}
	const mcpResolver = async(uri) => {
		if (typeof uri !== "string") return "";
		if (!uri.startsWith("ui://")) return "";
		const payload = await resolveUiResource(uri);
		console.info(
			"[mcp-test] bridge payload",
			{
				uri,
				payloadType: payload?.constructor?.name || typeof payload,
				payloadPreview: typeof payload === "string" ? payload.slice(0, 200) : null
			}
		);
		return payload;
	};
	const mcpToolCaller = async(params) => {
		if (!currentServer.value) {
			throw new Error("No server selected.");
		}
		const toolName = params?.name;
		if (!toolName || typeof toolName !== "string") {
			throw new Error("Tool name is required.");
		}
		const argumentsPayload = params?.arguments ?? {};
		const rawMeta = params?._meta;
		const meta = rawMeta && typeof rawMeta === "object" ? rawMeta : undefined;
		const payload = { toolName, json: JSON.stringify(argumentsPayload ?? {}), meta };
		const response = await invokeTool(currentServer.value, payload);
		if (response && typeof response === "object" && "result" in response) {
			return response.result;
		}
		return response;
	};
	function mcpThemeVars() {
		return {
			"--mcp-color-bg": "#0b1116",
			"--mcp-color-fg": "#e7eef5",
			"--mcp-color-muted": "#98a2b3",
			"--mcp-color-muted-fg": "#c6d0db",
			"--mcp-color-border": "#26323b",
			"--mcp-color-border-strong": "#3a4653",
			"--mcp-surface": "#111822",
			"--mcp-surface-alt": "#0f151d",
			"--mcp-color-primary": "#f97316",
			"--mcp-color-primary-fg": "#111827",
			"--mcp-color-secondary": "#141b22",
			"--mcp-color-secondary-fg": "#e7eef5",
			"--mcp-color-accent": "#fb923c",
			"--mcp-color-accent-fg": "#111827",
			"--mcp-color-success": "#22c55e",
			"--mcp-color-success-fg": "#0b1116",
			"--mcp-color-warning": "#fbbf24",
			"--mcp-color-warning-fg": "#111827",
			"--mcp-color-danger": "#f87171",
			"--mcp-color-danger-fg": "#111827",
			"--mcp-syntax-comment": "#6a9955",
			"--mcp-syntax-constant": "#4fc1ff",
			"--mcp-syntax-keyword": "#569cd6",
			"--mcp-syntax-entity": "#dcdcaa",
			"--mcp-syntax-variable": "#9cdcfe",
			"--mcp-syntax-string": "#ce9178",
			"--mcp-syntax-number": "#b5cea8",
			"--mcp-syntax-operator": "#d4d4d4",
			"--mcp-syntax-punctuation": "#d4d4d4",
			"--mcp-syntax-tag": "#4ec9b0",
			"--mcp-ring": "0 0 0 2px",
			"--mcp-ring-color": "rgba(249, 115, 22, 0.35)"
		};
	}
	function applyMcpViewResolvers() {
		const containers = [toolResponseContainer.value, applicationContainer.value].filter(Boolean);
		if (!containers.length) return;
		containers.forEach(
			(container) => {
				const views = container.querySelectorAll("mcp-view");
				views.forEach(
					(view) => {
						console.info("[mcp-test] attach resolver", { src: view.getAttribute("src") });
						view.resolver = mcpResolver;
						view.toolCaller = mcpToolCaller;
						view.css = mcpThemeVars();
					}
				);
			}
		);
	}
	function openApplication(app) {
		selectedApplication.value = app;
		activeTab.value = "Application";
		isIframeFullscreen.value = false;
	}
	function isApplicationActive(app) {
		return activeTab.value === "Application" && selectedApplication.value?.uri === app?.uri;
	}
	function shouldShowIframeForTab(type) {
		return !isContentLoading(type) && shouldRenderMcpView(activeContentEntry(type));
	}
	const showTools = ref(false);
	const showResources = ref(false);
	const showPrompts = ref(false);
	const serverDefinition = computed(
		() => {
			if (!currentServer.value) return null;
			if (!status.value.initialize) return null;
			const definition = { initialize: status.value.initialize };
			if (showTools.value) {
				definition.tools = facets.value.tools?.tools || null;
			}
			if (showResources.value) {
				definition.resources = facets.value.resources?.resources || null;
			}
			if (showPrompts.value) {
				definition.prompts = facets.value.prompts?.prompts || null;
			}
			return definition;
		}
	);
	const resourceList = computed(() => facets.value.resources?.resources || []);
	const promptList = computed(() => facets.value.prompts?.prompts || []);
	const currentServerName = computed(
		() => {
			return servers.value.find((server) => server.id === currentServer.value)?.name || currentServer.value;
		}
	);
	const selectedServer = computed(
		() => {
			return servers.value.find((server) => server.id === currentServer.value) || null;
		}
	);
	const recentLogLines = computed(() => logLines.value.slice(-20));
	const LOG_LINE_MAX = 180;
	const logEntries = computed(
		() => {
			const lines = [...logLines.value].reverse();
			return lines.map(
				(line) => {
					const json = parseJsonFromLog(line);
					return {
						line,
						json,
						isJson: json != null,
						displayLine: formatLogLine(line, json != null)
					};
				}
			);
		}
	);
	const logFilter = ref("json");
	const filteredLogEntries = computed(
		() => {
			if (logFilter.value === "all") return logEntries.value;
			if (logFilter.value === "json") {
				return logEntries.value.filter((entry) => entry.isJson);
			}
			return logEntries.value.filter((entry) => !entry.isJson);
		}
	);
	const selectedLogLine = ref("");
	const selectedLogJson = ref(null);
	const statusLabel = computed(
		() => {
			if (!currentServer.value) return "No server";
			if (connectionState.value === "connecting") return "Connecting";
			if (status.value.running) return "Connected";
			if (connectionState.value === "error") return "Connection failed";
			return "Disconnected";
		}
	);
	const statusClass = computed(
		() => {
			if (!currentServer.value) return "idle";
			if (connectionState.value === "connecting") return "connecting";
			if (status.value.running) return "connected";
			if (connectionState.value === "error") return "error";
			return "idle";
		}
	);
	const resourceVars = computed(
		() => {
			if (!selectedResource.value?.uri) return [];
			const matches = [...selectedResource.value.uri.matchAll(/\{([^}]+)\}/g)];
			return matches.map((m) => m[1]);
		}
	);
	async function loadServers() {
		servers.value = await listServers();
		const statusMap = {};
		for (const server of servers.value) {
			try {
				statusMap[server.id] = await getStatus(server.id);
			}
			catch {
				statusMap[server.id] = { running: false };
			}
		}
		serverStatuses.value = statusMap;
	}
	function attachLogStream(id) {
		if (logStream) logStream.close();
		logLines.value = [];
		logStream = openLogStream(
			id,
			(line) => {
				logLines.value.push(line);
				if (logLines.value.length > 200) {
					logLines.value.splice(0, logLines.value.length - 200);
				}
			}
		);
	}
	function sleep(ms) {
		return new Promise((resolve) => setTimeout(resolve, ms));
	}
	async function waitForStatus(id, running, { timeoutMs = 3000, intervalMs = 250 } = {}) {
		const start = Date.now();
		while (Date.now() - start < timeoutMs) {
			try {
				const latest = await getStatus(id);
				serverStatuses.value[id] = latest;
				if (latest?.running === running) return true;
			}
			catch {
				if (!running) return true;
			}
			await sleep(intervalMs);
		}
		return false;
	}
	async function loadFacets() {
		if (!currentServer.value) return;
		facets.value = await getFacets(currentServer.value);
	}
	async function refreshDefinition() {
		if (!currentServer.value) return;
		try {
			const latest = await getStatus(currentServer.value);
			status.value = latest;
			serverStatuses.value[currentServer.value] = latest;
			if (latest.running && (showTools.value || showResources.value || showPrompts.value)) {
				await loadFacets();
			}
		}
		catch {
			status.value = { running: false, capabilities: null, initialize: null };
			serverStatuses.value[currentServer.value] = status.value;
		}
	}
	async function createNewServer() {
		const envMap = parseEnv(newServer.value.env);
		const payload = {
			name: newServer.value.name,
			description: newServer.value.description,
			command: newServer.value.command,
			cwd: newServer.value.cwd,
			framing: newServer.value.framing,
			transport: newServer.value.transport,
			httpUrl: newServer.value.httpUrl,
			httpMessageUrl: newServer.value.httpMessageUrl,
			httpHeaders: parseEnv(newServer.value.httpHeaders),
			env: envMap
		};
		const created = await createServer(payload);
		await loadServers();
		currentServer.value = created.id;
		newServer.value = {
			name: "",
			description: "",
			command: "",
			cwd: "",
			framing: "ndjson",
			transport: "stdio",
			httpUrl: "",
			httpMessageUrl: "",
			httpHeaders: "",
			env: ""
		};
		showAddModal.value = false;
	}
	function startEdit(server) {
		editServerId.value = server.id;
		editServerForm.value = {
			name: server.name || "",
			description: server.description || "",
			command: server.command || "",
			cwd: server.cwd || "",
			framing: server.framing || "ndjson",
			transport: server.transport || "stdio",
			httpUrl: server.httpUrl || "",
			httpMessageUrl: server.httpMessageUrl || "",
			httpHeaders: envToText(server.httpHeaders),
			env: envToText(server.env)
		};
		showEditModal.value = true;
	}
	function cancelEdit() {
		editServerId.value = "";
		showEditModal.value = false;
	}
	async function saveServerEdits(id) {
		const payload = {
			name: editServerForm.value.name,
			description: editServerForm.value.description,
			command: editServerForm.value.command,
			cwd: editServerForm.value.cwd,
			framing: editServerForm.value.framing,
			transport: editServerForm.value.transport,
			httpUrl: editServerForm.value.httpUrl,
			httpMessageUrl: editServerForm.value.httpMessageUrl,
			httpHeaders: parseEnv(editServerForm.value.httpHeaders),
			env: parseEnv(editServerForm.value.env)
		};
		const updated = await updateServer(id, payload);
		await loadServers();
		if (currentServer.value === id) {
			currentServer.value = updated.id;
		}
		editServerId.value = "";
		showEditModal.value = false;
	}
	async function viewServer(id) {
		if (!id) return;
		connectionError.value = "";
		connectionState.value = "connecting";
		currentServer.value = id;
		activeTab.value = "Tools";
		try {
			const latestStatus = await getStatus(id);
			serverStatuses.value[id] = latestStatus;
			if (latestStatus?.running) {
				status.value = latestStatus;
				connectionState.value = "connected";
				await loadFacets();
				return;
			}
		}
		catch {
			serverStatuses.value[id] = { running: false };
		}
		try {
			serverStatuses.value[id] = await startServer(id);
			status.value = serverStatuses.value[id];
			if (status.value.running) {
				connectionState.value = "connected";
				await loadFacets();
			}
			else {
				connectionState.value = "error";
				connectionError.value = "Server did not report a running state.";
				activeTab.value = "Raw Log";
			}
		}
		catch (error) {
			connectionState.value = "error";
			connectionError.value = error?.message || "Failed to connect.";
			serverStatuses.value[id] = { running: false };
			status.value = { running: false, capabilities: null, initialize: null };
			activeTab.value = "Raw Log";
		}
	}
	async function restartServer(id) {
		if (!id) return;
		const server = servers.value.find((item) => item.id === id);
		if (!server || server.transport !== "stdio") return;
		if (!serverStatuses.value[id]?.running) return;
		const isCurrent = currentServer.value === id;
		if (isCurrent) {
			connectionError.value = "";
			connectionState.value = "connecting";
		}
		try {
			await stopServer(id);
			serverStatuses.value[id] = { running: false };
			if (isCurrent) {
				status.value = { running: false, capabilities: null, initialize: null };
			}
			await waitForStatus(id, false);
			let updatedStatus = null;
			let lastError = null;
			for (let attempt = 0; attempt < 3; attempt += 1) {
				try {
					updatedStatus = await startServer(id);
					break;
				}
				catch (error) {
					lastError = error;
					await sleep(400 + attempt * 300);
				}
			}
			if (!updatedStatus) {
				throw lastError || new Error("Failed to restart.");
			}
			serverStatuses.value[id] = updatedStatus;
			if (isCurrent) {
				status.value = updatedStatus;
				if (status.value.running) {
					connectionState.value = "connected";
					await loadFacets();
					attachLogStream(id);
				}
				else {
					connectionState.value = "error";
					connectionError.value = "Server did not report a running state.";
					activeTab.value = "Raw Log";
				}
			}
			else if (!updatedStatus?.running) {
				try {
					serverStatuses.value[id] = await getStatus(id);
				}
				catch {
					serverStatuses.value[id] = { running: false };
				}
			}
		}
		catch (error) {
			serverStatuses.value[id] = { running: false };
			if (isCurrent) {
				status.value = { running: false, capabilities: null, initialize: null };
				connectionState.value = "error";
				connectionError.value = error?.message || "Failed to restart.";
				activeTab.value = "Raw Log";
			}
		}
	}
	async function reconnectServer(id) {
		if (!id) return;
		const server = servers.value.find((item) => item.id === id);
		if (!server || server.transport === "stdio") return;
		if (!serverStatuses.value[id]?.running) return;
		const isCurrent = currentServer.value === id;
		if (isCurrent) {
			connectionError.value = "";
			connectionState.value = "connecting";
		}
		try {
			await stopServer(id);
			serverStatuses.value[id] = { running: false };
			if (isCurrent) {
				status.value = { running: false, capabilities: null, initialize: null };
			}
			await waitForStatus(id, false);
			let updatedStatus = null;
			let lastError = null;
			for (let attempt = 0; attempt < 3; attempt += 1) {
				try {
					updatedStatus = await startServer(id);
					break;
				}
				catch (error) {
					lastError = error;
					await sleep(400 + attempt * 300);
				}
			}
			if (!updatedStatus) {
				throw lastError || new Error("Failed to reconnect.");
			}
			serverStatuses.value[id] = updatedStatus;
			if (isCurrent) {
				status.value = updatedStatus;
				if (status.value.running) {
					connectionState.value = "connected";
					await loadFacets();
					attachLogStream(id);
				}
				else {
					connectionState.value = "error";
					connectionError.value = "Server did not report a running state.";
					activeTab.value = "Raw Log";
				}
			}
		}
		catch (error) {
			serverStatuses.value[id] = { running: false };
			if (isCurrent) {
				status.value = { running: false, capabilities: null, initialize: null };
				connectionState.value = "error";
				connectionError.value = error?.message || "Failed to reconnect.";
				activeTab.value = "Raw Log";
			}
		}
	}
	function selectTool(tool) {
		selectedTool.value = tool.name;
		selectedToolInfo.value = tool;
		toolPanelTab.value = "invoke";
		toolJson.value = "{}";
		toolResponse.value = null;
		toolResponseTab.value = "result";
		metaEntries.value = [];
		currentLoadedSavedId.value = "";
		currentLoadedInputMeta.value = { name: "", comment: "" };
		selectedSavedInputId.value = "";
		loadSavedInputs();
	}
	async function invokeSelected() {
		if (!selectedTool.value) return;
		const validation = validateJsonInput(toolJson.value);
		if (validation) {
			toolJsonError.value = validation;
			return;
		}
		toolJsonError.value = "";
		const meta = buildMetaMap();
		toolResponse.value = await invokeTool(currentServer.value, { toolName: selectedTool.value, json: toolJson.value, meta });
	}
	function addMeta() {
		metaEntries.value.push({ key: "", value: "" });
	}
	function removeMeta(index) {
		metaEntries.value.splice(index, 1);
	}
	function formatToolJson() {
		try {
			toolJson.value = formatJsonString(toolJson.value || "{}");
		}
		catch {
			return;
		}
	}
	function generateTemplate() {
		const schema = getToolInputSchema(selectedToolInfo.value);
		if (!schema) return;
		const template = buildTemplate(schema);
		toolJson.value = formatJsonValue(template);
	}
	async function loadSavedInputs() {
		if (!currentServer.value || !selectedTool.value) return;
		savedInputs.value = await listSavedInputs(currentServer.value, selectedTool.value);
	}
	function openSavedInputs() {
		showSavedInputs.value = true;
		showSaveInputs.value = false;
		savedInputsNotice.value = "";
		savedInputsError.value = "";
		loadSavedInputs();
	}
	function openSaveInputs() {
		showSaveInputs.value = true;
		showSavedInputs.value = false;
		savedInputsNotice.value = "";
		savedInputsError.value = "";
		saveInputDraft.value = { name: currentLoadedInputMeta.value.name || "", comment: currentLoadedInputMeta.value.comment || "" };
		loadSavedInputs();
	}
	function closeSaveInputs() {
		showSaveInputs.value = false;
	}
	async function saveNewInputFromSidebar() {
		if (!currentServer.value || !selectedTool.value || !saveInputDraft.value.name) return;
		const meta = buildMetaMap() || extractMetaFromJson(toolJson.value);
		try {
			await saveInput(
				currentServer
						.value,
				selectedTool
						.value,
				{
					name: saveInputDraft.value.name,
					comment: saveInputDraft.value.comment,
					json: toolJson.value,
					meta
				}
			);
			await loadSavedInputs();
			savedInputsNotice.value = "Saved new input.";
			savedInputsError.value = "";
			saveInputDraft.value = { name: "", comment: "" };
		}
		catch (error) {
			savedInputsNotice.value = "";
			savedInputsError.value = error?.message || "Failed to save input.";
		}
	}
	async function overwriteSavedInput(input) {
		if (!currentServer.value || !selectedTool.value || !input?.id) return;
		const meta = buildMetaMap() || extractMetaFromJson(toolJson.value);
		try {
			await updateSavedInput(
				currentServer
						.value,
				selectedTool
						.value,
				input
						.id,
				{
					name: input.name || "",
					comment: input.comment || "",
					json: toolJson.value,
					meta
				}
			);
			if (currentLoadedSavedId.value === input.id) {
				currentLoadedInputMeta.value = { name: input.name || "", comment: input.comment || "" };
			}
			await loadSavedInputs();
			savedInputsNotice.value = `Overwrote ${input.name || "saved input"}.`;
			savedInputsError.value = "";
		}
		catch (error) {
			savedInputsNotice.value = "";
			savedInputsError.value = error?.message || "Failed to overwrite input.";
		}
	}
	async function deleteSaved(input) {
		try {
			await deleteSavedInput(currentServer.value, selectedTool.value, input.id);
			await loadSavedInputs();
			savedInputsNotice.value = `Deleted ${input.name || "saved input"}.`;
			savedInputsError.value = "";
		}
		catch (error) {
			savedInputsNotice.value = "";
			savedInputsError.value = error?.message || "Failed to delete input.";
		}
	}
	function loadSavedInput(input) {
		toolJson.value = input.json || "{}";
		metaEntries.value = Object.entries(input.meta || {}).map(([key, value]) => ({ key, value }));
		currentLoadedSavedId.value = input.id || "";
		currentLoadedInputMeta.value = { name: input.name || "", comment: input.comment || "" };
		selectedSavedInputId.value = input.id || "";
		showSavedInputs.value = false;
	}
	function selectResource(res) {
		selectedResource.value = res;
		resourceResponse.value = null;
		resourceValues.value = {};
	}
	async function readSelectedResource() {
		if (!selectedResource.value) return;
		const uri = fillUri(selectedResource.value.uri, resourceValues.value);
		resourceResponse.value = await readResource(currentServer.value, { uri });
	}
	function selectPrompt(prompt) {
		selectedPrompt.value = prompt;
		promptJson.value = "{}";
		promptResponse.value = null;
	}
	async function getSelectedPrompt() {
		if (!selectedPrompt.value) return;
		const validation = validateJsonInput(promptJson.value);
		if (validation) {
			promptJsonError.value = validation;
			return;
		}
		promptJsonError.value = "";
		promptResponse.value = await getPrompt(currentServer.value, { name: selectedPrompt.value.name, json: promptJson.value });
	}
	function clearLog() {
		logLines.value = [];
		selectedLogLine.value = "";
		selectedLogJson.value = null;
	}
	function selectLogEntry(entry) {
		selectedLogLine.value = entry.line;
		selectedLogJson.value = entry.json;
	}
	function parseJsonFromLog(line) {
		if (!line) return null;
		const trimmed = line.trim();
		const markerIndex = findDirectionMarkerIndex(trimmed);
		if (markerIndex !== -1) {
			const payload = trimmed.slice(markerIndex + 3).trim();
			const parsed = safeParseJson(payload);
			if (parsed != null) return parsed;
		}
		const extracted = extractJsonPayload(trimmed);
		if (extracted) return safeParseJson(extracted);
		return safeParseJson(trimmed);
	}
	function formatLogLine(line, isJson) {
		if (!line) return "";
		if (!isJson || line.length <= LOG_LINE_MAX) return line;
		const markerIndex = findDirectionMarkerIndex(line);
		if (markerIndex !== -1) {
			const prefix = line.slice(0, markerIndex + 3);
			const payload = line.slice(markerIndex + 3).trimStart();
			const budget = LOG_LINE_MAX - prefix.length;
			if (budget <= 3) return elideText(line, LOG_LINE_MAX);
			return `${prefix}${elideText(payload, budget)}`;
		}
		const payloadStart = findJsonStartIndex(line);
		if (payloadStart > 0) {
			const prefix = line.slice(0, payloadStart);
			const payload = line.slice(payloadStart);
			const budget = LOG_LINE_MAX - prefix.length;
			if (budget <= 3) return elideText(line, LOG_LINE_MAX);
			return `${prefix}${elideText(payload, budget)}`;
		}
		return elideText(line, LOG_LINE_MAX);
	}
	function elideText(value, maxLength) {
		if (value.length <= maxLength) return value;
		return `${value.slice(0, maxLength - 3)}...`;
	}
	function findDirectionMarkerIndex(value) {
		const outIndex = value.indexOf(">> ");
		if (outIndex !== -1) return outIndex;
		return value.indexOf("<< ");
	}
	function findJsonStartIndex(value) {
		const objectIndex = value.indexOf("{");
		const arrayIndex = value.indexOf("[");
		if (objectIndex !== -1 && arrayIndex !== -1) {
			return Math.min(objectIndex, arrayIndex);
		}
		if (objectIndex !== -1) return objectIndex;
		if (arrayIndex !== -1) return arrayIndex;
		return -1;
	}
	function extractJsonPayload(value) {
		const objectIndex = value.indexOf("{");
		const arrayIndex = value.indexOf("[");
		let start = -1;
		if (objectIndex !== -1 && arrayIndex !== -1) {
			start = Math.min(objectIndex, arrayIndex);
		}
		else if (objectIndex !== -1) {
			start = objectIndex;
		}
		else if (arrayIndex !== -1) {
			start = arrayIndex;
		}
		if (start === -1) return null;
		return value.slice(start);
	}
	function safeParseJson(value) {
		try {
			return JSON.parse(value);
		}
		catch {
			return null;
		}
	}
	async function copySelectedJson() {
		if (!selectedLogJson.value) return;
		const payload = JSON.stringify(selectedLogJson.value, null, 2);
		try {
			await navigator.clipboard.writeText(payload);
		}
		catch {
			return;
		}
	}
	function parseEnv(text) {
		if (!text) return {};
		return text.split("\n")
			.reduce(
				(acc, line) => {
					const trimmed = line.trim();
					if (!trimmed) return acc;
					const [key, ...rest] = trimmed.split("=");
					acc[key] = rest.join("=");
					return acc;
				},
				{}
			);
	}
	function envToText(env) {
		if (!env) return "";
		return Object.entries(env).map(([key, value]) => `${key}=${value}`).join("\n");
	}
	function fillUri(template, values) {
		return template.replace(/\{([^}]+)\}/g, (_, key) => values[key] || "");
	}
	function getToolInputSchema(tool) {
		if (!tool) return null;
		return tool.inputSchema || tool.parameters || tool.schema || null;
	}
	function getToolOutputSchema(tool) {
		if (!tool) return null;
		return tool.outputSchema || tool.returns || tool.resultSchema || null;
	}
	function buildTemplate(schema) {
		if (!schema) return {};
		if (schema.oneOf && schema.oneOf.length) {
			return buildTemplate(schema.oneOf[0]);
		}
		if (schema.anyOf && schema.anyOf.length) {
			return buildTemplate(schema.anyOf[0]);
		}
		if (schema.enum && schema.enum.length) {
			return schema.enum[0];
		}
		const type = Array.isArray(schema.type) ? schema.type[0] : schema.type;
		if (type === "object" || schema.properties) {
			const result = {};
			const props = schema.properties || {};
			for (const key of Object.keys(props)) {
				result[key] = buildTemplate(props[key]);
			}
			return result;
		}
		if (type === "array" || schema.items) {
			const item = schema.items ? buildTemplate(schema.items) : null;
			return item == null ? [] : [item];
		}
		if (type === "number" || type === "integer") return 0;
		if (type === "boolean") return false;
		return "";
	}
	function buildMetaMap() {
		const meta = metaEntries.value
			.map((entry) => ({ key: (entry.key || "").trim(), value: entry.value == null ? "" : String(entry.value) }))
			.filter((entry) => entry.key)
			.reduce(
				(acc, entry) => {
					acc[entry.key] = entry.value;
					return acc;
				},
				{}
			);
		return Object.keys(meta).length ? meta : null;
	}
	function extractMetaFromJson(text) {
		try {
			const parsed = JSON.parse(text || "{}");
			const rawMeta = parsed && typeof parsed === "object" ? (parsed._meta || parsed.meta) : null;
			if (!rawMeta || typeof rawMeta !== "object") return null;
			const meta = Object.entries(rawMeta)
				.reduce(
					(acc, [key, value]) => {
						acc[String(key)] = value == null ? "" : String(value);
						return acc;
					},
					{}
				);
			return Object.keys(meta).length ? meta : null;
		}
		catch {
			return null;
		}
	}
	function formatJsonString(text) {
		const parsed = JSON.parse(text);
		return formatJsonValue(parsed);
	}
	function validateJsonInput(text) {
		if (!text || !text.trim()) return null;
		try {
			JSON.parse(text);
			return null;
		}
		catch (error) {
			const message = error?.message || "Invalid JSON";
			return `Invalid JSON: ${message}`;
		}
	}
	function formatJsonValue(value) {
		const spaces = indentStyle.value === "tabs" ? indentSize.value : indentSize.value;
		const raw = JSON.stringify(value, null, spaces);
		if (indentStyle.value !== "tabs") return raw;
		const unit = " ".repeat(indentSize.value);
		const matcher = new RegExp(`^(?:${unit})+`, "gm");
		return raw.replace(matcher, (match) => "\t".repeat(match.length / unit.length));
	}
	watch(
		currentServer,
		async(value) => {
			if (!value) {
				status.value = { running: false, capabilities: null, initialize: null };
				connectionState.value = "idle";
				connectionError.value = "";
				if (logStream) logStream.close();
				logStream = null;
				return;
			}
			selectedTool.value = "";
			selectedResource.value = null;
			selectedPrompt.value = null;
			selectedApplication.value = null;
			toolResponse.value = null;
			resourceResponse.value = null;
			promptResponse.value = null;
			toolJsonError.value = "";
			promptJsonError.value = "";
			savedInputs.value = [];
			currentLoadedSavedId.value = "";
			currentLoadedInputMeta.value = { name: "", comment: "" };
			selectedSavedInputId.value = "";
			attachLogStream(value);
			if (connectionState.value === "connecting") return;
			try {
				status.value = await getStatus(value);
				serverStatuses.value[value] = status.value;
				connectionState.value = status.value.running ? "connected" : "idle";
				if (status.value.running) {
					facets.value = await getFacets(value);
				}
			}
			catch (error) {
				status.value = { running: false, capabilities: null, initialize: null };
				serverStatuses.value[value] = status.value;
				connectionState.value = "error";
				connectionError.value = error?.message || "Failed to load server status.";
			}
		}
	);
	watch(
		toolJson,
		() => {
			toolJsonError.value = "";
		}
	);
	watch(
		toolResponse,
		() => {
			activeContentPayloads.value = {};
			activeContentLoading.value = {};
		}
	);
	watch(
		promptJson,
		() => {
			promptJsonError.value = "";
		}
	);
	watch(
		responseContentTypes,
		(types) => {
			if (toolResponseTab.value === "result" || toolResponseTab.value === "meta") return;
			if (!types.includes(toolResponseTab.value)) {
				toolResponseTab.value = "result";
			}
		}
	);
	watch(
		[toolResponseTab, responseContentTypes, toolResponse, currentServer],
		async() => {
			if (toolResponseTab.value !== "result" && toolResponseTab.value !== "meta") {
				await loadContentForType(toolResponseTab.value);
			}
			await nextTick();
			applyMcpViewResolvers();
		}
	);
	watch(
		[selectedApplication, activeTab, currentServer],
		async() => {
			if (activeTab.value !== "Application") return;
			await nextTick();
			applyMcpViewResolvers();
		}
	);
	watch(
		[toolResponseTab, responseContentTypes, toolResponse, currentServer],
		() => {
			const entry = activeContentEntry(toolResponseTab.value);
			if (!entry || !shouldRenderMcpView(entry)) {
				isIframeFullscreen.value = false;
			}
		}
	);
	watch(
		activeTab,
		(tab) => {
			if (tab !== "Application" && tab !== "Tools") {
				isIframeFullscreen.value = false;
			}
		}
	);
	watch(
		[showTools, showResources, showPrompts],
		async([tools, resources, prompts]) => {
			if (!currentServer.value || !status.value.running) return;
			if (!(tools || resources || prompts)) return;
			if (facets.value.tools || facets.value.resources || facets.value.prompts) return;
			await loadFacets();
		}
	);
	function isTabDisabled(tab) {
		if (tab === "Overview") return false;
		if (tab === "Raw Log") return !currentServer.value;
		if (tab === "Definition") return !currentServer.value;
		return !status.value.running;
	}
	onMounted(
		async() => {
			await loadServers();
			window.addEventListener("keydown", handleFullscreenKeydown);
		}
	);
	onUnmounted(
		() => {
			window.removeEventListener("keydown", handleFullscreenKeydown);
		}
	);
	function handleFullscreenKeydown(event) {
		if (event.key !== "Escape") return;
		if (!isIframeFullscreen.value) return;
		isIframeFullscreen.value = false;
	}
</script>
<style>
	html, body, #app {
		margin: 0;
		padding: 0;
		height: 100%;
		background: #0b1116;
		color: #e7eef5;
	}
</style>
<style scoped>
	.app {
		--color-bg: #0b1116;
		--color-bg-alt: #141b22;
		--color-panel: #10171e;
		--color-panel-strong: #0c1218;
		--color-border: #26323b;
		--color-text: #e7eef5;
		--color-muted: #98a2b3;
		--color-accent: #f97316;
		--color-accent-strong: #fb923c;
		--color-success: #22c55e;
		--color-danger: #f87171;
		--color-warning: #fbbf24;
		--color-shadow: rgba(0, 0, 0, 0.5);
		font-family: "IBM Plex Sans", "Segoe UI", sans-serif;
		color: var(--color-text);
		background: radial-gradient(circle at 20% 0%, #1c2730, #0b1116 55%, #070b0f 100%);
		min-height: 100vh;
		padding: 24px;
	}

	.app.fullscreen-active::before {
		content: "";
		position: fixed;
		inset: 0;
		background: rgba(5, 8, 12, 0.88);
		z-index: 70;
		pointer-events: none;
	}

	.app-shell {
		display: block;
	}

	.top-bar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 24px;
		padding: 16px 20px;
		border-radius: 14px;
		background: linear-gradient(130deg, rgba(16, 23, 30, 0.98), rgba(12, 18, 24, 0.92));
		border: 1px solid var(--color-border);
		box-shadow: 0 16px 30px var(--color-shadow);
		margin-bottom: 20px;
	}

	.top-bar-right {
		display: flex;
		align-items: center;
		gap: 16px;
		flex-wrap: wrap;
		justify-content: flex-end;
	}

	.top-bar-actions {
		display: flex;
		align-items: center;
		gap: 12px;
		flex-wrap: wrap;
		justify-content: flex-end;
	}

	.indent-control {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 6px 10px;
		border-radius: 999px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.75);
	}

	.indent-label {
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: 0.6px;
		color: var(--color-muted);
	}

	.indent-button {
		border: 1px solid transparent;
		background: transparent;
		color: var(--color-text);
		padding: 4px 10px;
		border-radius: 999px;
		font-size: 12px;
		font-weight: 600;
	}

	.indent-button.active {
		border-color: rgba(249, 115, 22, 0.6);
		color: var(--color-accent-strong);
		background: rgba(249, 115, 22, 0.18);
	}

	.brand {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.brand-title {
		font-size: 20px;
		font-weight: 700;
		letter-spacing: 0.3px;
	}

	.brand-sub {
		font-size: 12px;
		color: var(--color-muted);
		text-transform: uppercase;
		letter-spacing: 0.8px;
	}

	.toolbar-back {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		border-radius: 10px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
		color: var(--color-text);
		padding: 6px 10px;
		font-size: 12px;
	}

	.toolbar-back svg {
		width: 18px;
		height: 18px;
	}

	.toolbar-server {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 6px 10px;
		border-radius: 10px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
	}

	.server-label {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}

	.server-name {
		font-weight: 600;
	}

	.server-id {
		font-size: 11px;
		color: var(--color-muted);
	}

	.toolbar-tabs {
		display: inline-flex;
		gap: 8px;
		flex-wrap: wrap;
		justify-content: flex-start;
		align-items: center;
	}

	.section-tabs {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 14px;
		border: 1px solid var(--color-border);
		background: rgba(12, 18, 24, 0.85);
		box-shadow: 0 12px 18px rgba(0, 0, 0, 0.25);
		align-items: center;
		justify-content: space-between;
	}

	.section-tabs-group {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
		align-items: center;
	}

	.section-tabs-separator {
		width: 1px;
		height: 24px;
		background: var(--color-border);
		opacity: 0.7;
	}

	.section-tabs button {
		border: 1px solid transparent;
		background: rgba(15, 23, 30, 0.8);
		color: var(--color-text);
		padding: 8px 14px;
		border-radius: 999px;
		font-size: 13px;
	}

	.section-tabs button.active {
		border-color: rgba(249, 115, 22, 0.6);
		color: var(--color-accent-strong);
		background: rgba(249, 115, 22, 0.18);
	}

	.section-tabs-status {
		margin-left: auto;
		display: inline-flex;
		align-items: center;
		gap: 10px;
		padding: 6px 10px;
		border-radius: 10px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
	}

	.toolbar-tabs.subtabs {
		padding: 6px;
		border-radius: 999px;
		background: rgba(15, 23, 30, 0.8);
		border: 1px solid var(--color-border);
		align-items: center;
	}

	.toolbar-tabs.subtabs button {
		font-size: 13px;
	}

	.main {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.panel {
		margin-top: 16px;
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.definition-panel {
		min-height: calc(100vh - 280px);
	}

	.application-panel {
		min-height: calc(100vh - 280px);
	}

	.application-panel .application-card {
		display: flex;
		flex-direction: column;
		flex: 1;
	}

	.application-panel .content-selected {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-height: 0;
	}

	.application-container {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-height: 0;
	}

	.application-panel .content-mcp-view {
		flex: 1;
		min-height: 0;
		height: 100%;
	}

	.definition-card {
		display: flex;
		flex-direction: column;
		flex: 1;
	}

	.definition-viewer {
		flex: 1;
		display: flex;
		min-height: 360px;
		width: 100%;
		--json-viewer-height: 100%;
	}

	.definition-controls {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		margin-bottom: 12px;
	}

	.toggle-button {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
		color: var(--color-text);
		padding: 6px 12px;
		border-radius: 999px;
		font-size: 12px;
		font-weight: 600;
		cursor: pointer;
		user-select: none;
	}

	.toggle-button.active {
		border-color: rgba(249, 115, 22, 0.6);
		color: var(--color-accent-strong);
		background: rgba(249, 115, 22, 0.18);
	}

	.toggle-button.disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.toggle-button input {
		width: 14px;
		height: 14px;
		margin: 0;
		border-radius: 4px;
		border: 1px solid var(--color-border);
		background: rgba(12, 18, 24, 0.9);
		appearance: none;
		display: inline-grid;
		place-content: center;
	}

	.toggle-button input::after {
		content: "";
		width: 8px;
		height: 8px;
		border-radius: 2px;
		background: var(--color-accent-strong);
		transform: scale(0);
		transition: transform 0.12s ease;
	}

	.toggle-button input:checked::after {
		transform: scale(1);
	}

	.card {
		background: var(--color-panel);
		border: 1px solid var(--color-border);
		border-radius: 12px;
		padding: 16px;
		box-shadow: 0 14px 24px rgba(0, 0, 0, 0.35);
	}

	.grid {
		display: grid;
		gap: 12px;
	}

	.overview-grid {
		display: grid;
		gap: 16px;
		grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
	}

	button.add-card {
		background: rgba(16, 23, 30, 0.85);
		border: 1px dashed rgba(249, 115, 22, 0.5);
		color: var(--color-text);
		cursor: pointer;
		text-align: left;
	}

	.add-card-content {
		display: flex;
		align-items: center;
		gap: 16px;
	}

	.add-icon {
		width: 40px;
		height: 40px;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		border-radius: 12px;
		border: 1px solid rgba(249, 115, 22, 0.6);
		color: var(--color-accent-strong);
		font-size: 28px;
		line-height: 1;
	}

	.server-card {
		display: flex;
		flex-direction: column;
		gap: 12px;
		border-color: rgba(38, 50, 59, 0.9);
	}

	.server-card.active {
		border-color: rgba(249, 115, 22, 0.7);
		box-shadow: 0 0 0 1px rgba(249, 115, 22, 0.35), 0 20px 28px rgba(0, 0, 0, 0.45);
	}

	.server-card-header {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 12px;
	}

	.split {
		display: grid;
		grid-template-columns: 280px 1fr;
		gap: 16px;
	}

	.list {
		list-style: none;
		padding: 0;
		margin: 12px 0 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.list li {
		border: 1px solid var(--color-border);
		border-radius: 8px;
		padding: 8px 10px;
		display: flex;
		justify-content: space-between;
		align-items: center;
		cursor: pointer;
	}

	.server-list li {
		flex-direction: column;
		align-items: stretch;
		cursor: default;
	}

	.server-row {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 12px;
	}

	.edit-panel {
		margin-top: 12px;
		padding-top: 12px;
		border-top: 1px solid var(--color-border);
	}

	.status-row {
		margin-top: 6px;
	}

	.tag-row {
		display: flex;
		gap: 6px;
		margin-top: 6px;
		flex-wrap: wrap;
	}

	.tag {
		font-size: 11px;
		padding: 2px 8px;
		border-radius: 999px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
		color: var(--color-muted);
	}

	.tag.supported {
		border-color: rgba(249, 115, 22, 0.5);
		color: var(--color-accent-strong);
		background: rgba(249, 115, 22, 0.12);
	}

	.tag.unsupported {
		border-color: var(--color-border);
		color: var(--color-muted);
		background: rgba(15, 23, 30, 0.8);
	}

	.status {
		display: inline-flex;
		align-items: center;
		padding: 2px 8px;
		border-radius: 999px;
		font-size: 12px;
		border: 1px solid var(--color-border);
		color: var(--color-muted);
		background: rgba(15, 23, 30, 0.8);
	}

	.status.running {
		color: #bbf7d0;
		border-color: rgba(34, 197, 94, 0.8);
		background: rgba(34, 197, 94, 0.12);
	}

	.status.stopped {
		color: #fecaca;
		border-color: rgba(248, 113, 113, 0.7);
		background: rgba(248, 113, 113, 0.1);
	}

	.status-pill {
		display: inline-flex;
		align-items: center;
		padding: 6px 12px;
		border-radius: 999px;
		font-size: 12px;
		font-weight: 600;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
		color: var(--color-muted);
	}

	.status-pill.connected {
		color: #bbf7d0;
		border-color: rgba(34, 197, 94, 0.8);
		background: rgba(34, 197, 94, 0.15);
	}

	.status-pill.connecting {
		color: #fde68a;
		border-color: rgba(251, 191, 36, 0.8);
		background: rgba(251, 191, 36, 0.12);
	}

	.status-pill.error {
		color: #fecaca;
		border-color: rgba(248, 113, 113, 0.8);
		background: rgba(248, 113, 113, 0.12);
	}

	.status-pill.idle {
		color: var(--color-muted);
	}

	.list li.selected {
		border-color: rgba(249, 115, 22, 0.6);
		background: rgba(249, 115, 22, 0.08);
	}

	.list.compact li {
		cursor: pointer;
	}

	.muted {
		color: var(--color-muted);
		font-size: 12px;
	}

	.field-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 8px;
	}

	.field-row-actions {
		display: inline-flex;
		gap: 8px;
	}

	.panel-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		margin-bottom: 12px;
	}

	.response-tabs {
		display: inline-flex;
		gap: 6px;
		padding: 4px;
		border-radius: 999px;
		background: rgba(15, 23, 30, 0.8);
		border: 1px solid var(--color-border);
	}

	.response-tabs button {
		border: none;
		background: transparent;
		color: #475569;
		padding: 4px 10px;
		border-radius: 999px;
		font-size: 12px;
		font-weight: 600;
	}

	.response-tabs button.active {
		background: rgba(249, 115, 22, 0.18);
		color: var(--color-accent-strong);
	}

	.tool-response {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}

	.content-panel {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}

	.content-entry {
		padding: 12px;
		border-radius: 12px;
		border: 1px solid var(--color-border);
		background: rgba(12, 18, 24, 0.6);
	}

	.content-selected {
		padding: 12px;
		border-radius: 12px;
		border: 1px solid var(--color-border);
		background: rgba(12, 18, 24, 0.6);
	}

	.content-selected-toolbar {
		display: flex;
		justify-content: flex-end;
		margin-bottom: 10px;
	}

	.content-selected-toolbar.fullscreen {
		position: fixed;
		top: 24px;
		right: 24px;
		z-index: 100;
		margin: 0;
		padding: 0;
	}

	.content-selected-toolbar.fullscreen .ghost {
		padding: 6px 10px;
		font-size: 12px;
		border-radius: 999px;
		background: rgba(8, 12, 18, 0.7);
		border-color: rgba(38, 50, 59, 0.8);
	}

	.content-text-list {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}

	.content-text {
		margin: 0;
		white-space: pre-wrap;
		font-family: "IBM Plex Mono", "SFMono-Regular", "Menlo", monospace;
		font-size: 13px;
		color: #d1d5db;
	}

	.content-diff {
		margin: 0;
		white-space: pre-wrap;
		font-family: "IBM Plex Mono", "SFMono-Regular", "Menlo", monospace;
		font-size: 12px;
		line-height: 1.5;
		color: #cbd5f5;
		background: rgba(8, 12, 18, 0.6);
		border-radius: 10px;
		padding: 12px;
		overflow-x: auto;
	}

	.diff-line {
		display: block;
		padding: 2px 6px;
		border-radius: 6px;
	}

	.diff-added {
		color: #bbf7d0;
		background: rgba(34, 197, 94, 0.15);
	}

	.diff-removed {
		color: #fecaca;
		background: rgba(248, 113, 113, 0.18);
	}

	.diff-hunk {
		color: #fcd34d;
		background: rgba(251, 191, 36, 0.18);
	}

	.content-mcp-view {
		width: 100%;
		height: 420px;
		border-radius: 10px;
		overflow: hidden;
		background: #0b1116;
	}

	.content-mcp-view.fullscreen {
		position: fixed;
		top: 72px;
		right: 24px;
		bottom: 24px;
		left: 24px;
		width: auto;
		height: auto;
		z-index: 90;
		border-radius: 16px;
		box-shadow: 0 30px 60px rgba(0, 0, 0, 0.6);
	}

	.panel-header h2 {
		margin: 0;
	}

	.refresh-button {
		border: 1px solid rgba(249, 115, 22, 0.45);
		background: rgba(249, 115, 22, 0.15);
		color: #fed7aa;
		padding: 6px 12px;
		border-radius: 999px;
		font-size: 12px;
	}

	.refresh-button:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.button-row {
		display: flex;
		gap: 8px;
		margin: 12px 0;
		flex-wrap: wrap;
	}

	.button-row.column {
		flex-direction: column;
		align-items: stretch;
	}

	.inline-label {
		display: flex;
		flex-direction: column;
		gap: 6px;
		font-size: 12px;
		color: #52606d;
	}

	.button-row.compact {
		margin: 10px 0 0;
		gap: 6px;
	}

	.meta {
		margin-top: 16px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.meta-row {
		display: grid;
		grid-template-columns: 1fr 1fr auto;
		gap: 8px;
	}

	.saved {
		margin-top: 20px;
	}

	.saved-new-card {
		display: flex;
		flex-direction: column;
		gap: 12px;
		margin-bottom: 16px;
	}

	.schema {
		margin-top: 16px;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}

	.subtabs {
		display: inline-flex;
		gap: 8px;
		padding: 6px;
		border-radius: 999px;
		background: rgba(15, 23, 30, 0.8);
		border: 1px solid var(--color-border);
		margin-bottom: 12px;
	}

	.subtabs button {
		border: none;
		background: transparent;
		color: #475569;
		padding: 6px 12px;
		border-radius: 999px;
		font-size: 12px;
	}

	.subtabs button.active {
		background: rgba(249, 115, 22, 0.18);
		color: var(--color-accent-strong);
	}

	.log {
		background: #0b1116;
		color: #d1dae3;
		padding: 16px;
		border-radius: 8px;
		border: 1px solid var(--color-border);
		max-height: 400px;
		overflow: auto;
	}

	.log-split {
		display: grid;
		grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
		gap: 16px;
		align-items: stretch;
	}

	.log-filters {
		display: inline-flex;
		gap: 8px;
		padding: 6px;
		border-radius: 999px;
		border: 1px solid var(--color-border);
		background: rgba(15, 23, 30, 0.8);
		margin-bottom: 12px;
	}

	.log-filters button {
		border: 1px solid transparent;
		background: transparent;
		color: var(--color-muted);
		padding: 6px 12px;
		border-radius: 999px;
		font-size: 12px;
	}

	.log-filters button.active {
		border-color: rgba(249, 115, 22, 0.6);
		color: var(--color-accent-strong);
		background: rgba(249, 115, 22, 0.18);
	}

	.log-list {
		background: #0b1116;
		border: 1px solid var(--color-border);
		border-radius: 8px;
		max-height: 420px;
		overflow: auto;
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 8px;
	}

	.log-entry {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 6px 8px;
		border-radius: 6px;
		border: 1px solid transparent;
		color: #d1dae3;
		background: transparent;
		cursor: pointer;
		font-family: "IBM Plex Mono", "SFMono-Regular", Menlo, monospace;
		font-size: 12px;
		line-height: 1.4;
	}

	.log-entry:hover {
		background: rgba(249, 115, 22, 0.08);
	}

	.log-entry.selected {
		border-color: rgba(249, 115, 22, 0.6);
		background: rgba(249, 115, 22, 0.14);
	}

	.log-text {
		flex: 1;
		white-space: pre-wrap;
		word-break: break-word;
	}

	.log-chip {
		font-size: 10px;
		text-transform: uppercase;
		letter-spacing: 0.6px;
		padding: 2px 6px;
		border-radius: 999px;
		background: rgba(34, 197, 94, 0.16);
		color: #86efac;
		border: 1px solid rgba(34, 197, 94, 0.4);
	}

	.log-detail {
		background: #0b1116;
		border: 1px solid var(--color-border);
		border-radius: 8px;
		padding: 12px;
		display: flex;
		flex-direction: column;
		gap: 12px;
		max-height: 420px;
		overflow: auto;
	}

	.log-detail-raw {
		margin: 0;
		white-space: pre-wrap;
		word-break: break-word;
		color: #d1dae3;
		font-family: "IBM Plex Mono", "SFMono-Regular", Menlo, monospace;
		font-size: 12px;
	}

	.log-detail-json {
		flex: 1;
		min-height: 200px;
	}

	input, select, textarea, button {
		font-family: inherit;
	}

	input, select, textarea {
		border: 1px solid var(--color-border);
		border-radius: 8px;
		padding: 8px;
		width: 100%;
		box-sizing: border-box;
		background: var(--color-panel-strong);
		color: var(--color-text);
	}

	button {
		border: none;
		background: linear-gradient(140deg, var(--color-accent), var(--color-accent-strong));
		color: #111827;
		padding: 8px 12px;
		border-radius: 8px;
		cursor: pointer;
		font-weight: 600;
	}

	button.ghost {
		background: transparent;
		color: var(--color-text);
		border: 1px solid var(--color-border);
	}

	button:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.empty-state {
		text-align: center;
		padding: 40px 24px;
	}

	.empty-state.disconnected {
		border: 1px dashed rgba(248, 113, 113, 0.5);
		background: rgba(17, 24, 32, 0.8);
		width: fit-content;
		max-width: 720px;
		margin: 0 auto;
		align-self: center;
		display: inline-flex;
		flex-direction: column;
	}

	.plug-icon {
		width: 48px;
		height: 48px;
		margin: 0 auto 12px;
		color: var(--color-danger);
	}

	.plug-icon svg {
		width: 100%;
		height: 100%;
	}

	.log-preview {
		margin-top: 16px;
		text-align: left;
	}

	.disconnect-banner {
		display: inline-flex;
		align-items: center;
		gap: 12px;
		padding: 10px 12px;
		border-radius: 10px;
		border: 1px solid rgba(248, 113, 113, 0.5);
		background: rgba(248, 113, 113, 0.08);
		margin-bottom: 12px;
		align-self: center;
	}

	.banner {
		margin: 12px 0;
		padding: 8px 12px;
		border-radius: 8px;
		background: rgba(15, 23, 30, 0.8);
		border: 1px solid var(--color-border);
		color: var(--color-muted);
		font-size: 13px;
	}

	.banner.notice {
		border-color: rgba(34, 197, 94, 0.6);
		color: #bbf7d0;
		background: rgba(34, 197, 94, 0.12);
	}

	.banner.error {
		border-color: rgba(248, 113, 113, 0.6);
		color: #fecaca;
		background: rgba(248, 113, 113, 0.12);
	}

	.meta-chip {
		font-size: 11px;
		padding: 2px 8px;
		border-radius: 999px;
		background: rgba(56, 189, 248, 0.14);
		border: 1px solid rgba(56, 189, 248, 0.55);
		color: #7dd3fc;
	}

	.modal-overlay {
		position: fixed;
		inset: 0;
		background: rgba(7, 11, 15, 0.7);
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 24px;
		z-index: 20;
	}

	.modal {
		width: min(720px, 100%);
		background: var(--color-panel);
		border: 1px solid var(--color-border);
		border-radius: 16px;
		padding: 24px;
		box-shadow: 0 24px 40px rgba(0, 0, 0, 0.5);
	}

	.sidepane-overlay {
		position: fixed;
		inset: 0;
		background: rgba(7, 11, 15, 0.7);
		display: flex;
		justify-content: flex-end;
		z-index: 25;
	}

	.sidepane {
		width: min(420px, 100%);
		height: 100%;
		background: var(--color-panel);
		border-left: 1px solid var(--color-border);
		padding: 20px;
		display: flex;
		flex-direction: column;
		gap: 16px;
		box-shadow: -20px 0 40px rgba(0, 0, 0, 0.45);
		animation: sidepane-in 0.18s ease;
	}

	.sidepane-header {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 12px;
	}

	.sidepane-body {
		flex: 1;
		overflow: auto;
		padding-right: 4px;
	}

	.saved-list li {
		flex-direction: column;
		align-items: stretch;
		gap: 10px;
		cursor: default;
	}

	.saved-list .button-row {
		justify-content: flex-start;
	}

	@keyframes sidepane-in {
		from {
			transform: translateX(100%);
			opacity: 0;
		}
		to {
			transform: translateX(0);
			opacity: 1;
		}
	}

	.modal .grid {
		gap: 16px;
	}

	.modal-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		margin-bottom: 12px;
	}

	.icon-button {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 36px;
		height: 36px;
		border-radius: 10px;
		border: none;
		background: transparent;
		color: var(--color-text);
	}

	.icon-button svg {
		width: 20px;
		height: 20px;
	}

	@media (max-width: 900px) {
		.toolbar-tabs {
			justify-content: flex-start;
		}
		.top-bar-actions {
			width: 100%;
			justify-content: space-between;
		}
		.top-bar-right {
			width: 100%;
			justify-content: space-between;
		}
		.split {
			grid-template-columns: 1fr;
		}
		.top-bar {
			flex-direction: column;
			align-items: flex-start;
		}
		.sidepane {
			width: 100%;
		}
	}
</style>
