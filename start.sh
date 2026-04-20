echo Start build and deploy

docker build -t ev-request-bot .
docker compose -f docker-compose.yml up -d