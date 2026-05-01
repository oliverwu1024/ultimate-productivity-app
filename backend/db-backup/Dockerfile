FROM postgres:16-alpine
RUN apk add --no-cache aws-cli bash
COPY backup.sh /usr/local/bin/backup.sh
RUN chmod +x /usr/local/bin/backup.sh
CMD ["/usr/local/bin/backup.sh"]
