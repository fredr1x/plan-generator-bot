-- SHA-256 of the PDF the pages of a book were read from: the same PDF is never scanned (and paid) twice.
ALTER TABLE books ADD COLUMN pdf_sha256 TEXT;
CREATE INDEX books_pdf_sha256 ON books (pdf_sha256);
