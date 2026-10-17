export const docx = Buffer.from(
  "UEsDBBQAAAAIAOFuUV33S4B1wgAAAHYBAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbH2QuQ7CMAyGX6XKiqgRAwOiLMAKDLyAlbptRC7FLsfbk3INCBjt//gsLw7XSFxcnPVcqU4kzgFYd+SQyxDJZ6UJyaHkMbUQUR+xJZhOJjPQwQt5GcvQoZaLNTXYWyk2l7xmE3ylEllWxephHFiVwhit0ShZh5OvPyjjJ6HMybuHOxN5lA0KvhIG5TfgmdudKCVTU7HHJFt02QXnkGqog+5dTpb/a77cGZrGaHrnh7aYgiZm41tny7fi0PjX/XB/9/IGUEsDBBQAAAAIAOFuUV1hey9DiQAAAPIAAAALAAAAX3JlbHMvLnJlbHONzzsOAiEQBuCrEA6ws1pYGKCy2dZ4AQLDIy6PDBj19lJYrMbCcuaffH9GnHHVPZbcQqyNPdKam+Sh93oEaCZg0m0qFfNIXKGk+xjJQ9Xmqj3Cfp4PQFuDK7E12WIlp8XuOLs8K/5jF+eiwVMxt4S5/6j4uhiyJo9d8nshC/a9ngbLQQn4eFG9AFBLAwQUAAAACADhblFdv//DNusAAAC8AQAAEQAAAHdvcmQvZG9jdW1lbnQueG1sbVHLTgQhEPwVMh9goyYeyCyXTdST2UQTzwzgzCS8Ar3i/r0M4LiHvfQjVFdVN2Nmysuz1Q7JjzUusXwYFsTAAJJctBXpzgftytuXj1ZgaeMM2UcVopc6pdXN1sADpU9gxeoGPmY2eXXZcqjhFGt6x4vRJLNvYQ7DqxaqTN4PwEfYMTUgPxp/Vm86IfksOmRp2A2HFR3bzK7QZhvF1Ah7h/y5ukatSBBRzFGE5SYRTqanU4RevcRVbeVc8tGb4r0c55FS2l1fQbDJyRY7Rbja6UNMZXupjbkhD20UGg/sbpKWWLng76bw/1/8F1BLAQIUABQAAAAIAOFuUV33S4B1wgAAAHYBAAATAAAAAAAAAAAAAACAAQAAAABbQ29udGVudF9UeXBlc10ueG1sUEsBAhQAFAAAAAgA4W5RXWF7L0OJAAAA8gAAAAsAAAAAAAAAAAAAAIAB8wAAAF9yZWxzLy5yZWxzUEsBAhQAFAAAAAgA4W5RXb//wzbrAAAAvAEAABEAAAAAAAAAAAAAAIABpQEAAHdvcmQvZG9jdW1lbnQueG1sUEsFBgAAAAADAAMAuQAAAL8CAAAAAA==",
  "base64",
);

export function pdfFixture() {
  const stream = (label: string) => `BT /F1 18 Tf 30 150 Td (${label}) Tj ET`;
  const first = stream("CloudNest PDF preview / page one"),
    second = stream("Page two renders correctly");
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 260] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${first.length} >>\nstream\n${first}\nendstream`,
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 260] /Resources << /Font << /F1 4 0 R >> >> /Contents 7 0 R >>",
    `<< /Length ${second.length} >>\nstream\n${second}\nendstream`,
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  for (const [i, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(pdf));
    pdf += `${i + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 8\n0000000000 65535 f \n${offsets
    .slice(1)
    .map((n) => n.toString().padStart(10, "0") + " 00000 n ")
    .join(
      "\n",
    )}\ntrailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;

  return Buffer.from(pdf);
}
