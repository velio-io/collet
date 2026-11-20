export async function api(path, body, options = {}) {
  const response = await fetch(
    `/api${path}`,
    body === undefined
      ? options
      : {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Collet-Workbench": "1",
          },
          body: JSON.stringify(body),
        },
  );
  const result = await response.json();
  if (!response.ok)
    throw Object.assign(new Error(result.error?.message || "Request failed"), {
      details: result.error,
      status: response.status,
    });
  return result;
}
export const label = (value) => String(value ?? "").replace(/^:/, "");
export const title = (value) =>
  label(value)
    .replaceAll("-", " ")
    .replace(/^./, (x) => x.toUpperCase());
export const cell = (value) =>
  typeof value === "object" ? JSON.stringify(value) : String(value ?? "nil");
