# API Research

Use this skill when the user asks you to fetch data from a public API and summarize it.

Steps:
1. Identify a real endpoint. If the user did not give one, use a well-known public API
   (for example PubMed E-utilities: `https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&retmode=json&term=...`).
2. Call `http.request` with method GET and a bounded `maxBytes`. Private and loopback addresses are blocked.
3. Read the returned status. If it is not 2xx, report the real error; do not fabricate data.
4. Extract only fields that exist in the response. Note anything that was empty.
5. Save a Markdown report with `file.write` under `exports/` and include the source URL and
   the time of the request.
6. Report the file path and a short summary of the findings.

Never invent records, authors or dates that are not in the response body.
