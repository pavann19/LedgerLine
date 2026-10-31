FROM python:3.12-slim@sha256:09f7da3bc104798d0afb40bc08d23ab2da20a76130cec1f2ef170848f5d85217
RUN useradd --system --uid 10001 --create-home agent && mkdir /state && chown agent /state
WORKDIR /app
COPY worker.py /app/worker.py
USER 10001:10001
VOLUME ["/state"]
ENTRYPOINT ["python", "-B", "/app/worker.py", "--database", "/state/jobs.sqlite"]
