function createApiClient(fetchRequest) {
  return async (path, options = {}) => {
    const response = await fetchRequest('/api' + path, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }
    });
    let data = {};
    try { data = await response.json(); } catch {}
    if (!response.ok) {
      const error = new Error(data.error || `Erro HTTP ${response.status}`);
      error.code = data.code;
      error.runId = data.runId;
      error.status = response.status;
      throw error;
    }
    return data;
  };
}

// Resolve fetch when sending: the local profile installs its wrapper after load.
const api = createApiClient((...args) => fetch(...args));
if (typeof module !== 'undefined' && module.exports) module.exports = { createApiClient };
