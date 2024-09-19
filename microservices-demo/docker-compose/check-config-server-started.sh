#!/bin/bash
# check-config-server-started.sh

# Actualiza el repositorio para instalar el comando "curl"
apt-get update -y

# Instala el comando "curl" para poder realizar peticiones http
yes | apt-get install curl

# Realiza una primera petición http al servidor de configuraciones para obtener su estado
curlResult=$(curl -s -o /dev/null -I -w "%{http_code}" http://config-server:8888/actuator/health)

echo "result status code:" "$curlResult"

# Si la primera petición http no ha devuelto un código de estado 200, volvemos a relizar más peticiones http hasta que se obtenga ese código
while [[ ! $curlResult == "200" ]]; do
  >&2 echo "Config server is not up yet!"
  sleep 2
  curlResult=$(curl -s -o /dev/null -I -w "%{http_code}" http://config-server:8888/actuator/health)
done

# Por último, ejecuta la aplicación Spring Boot
./cnb/lifecycle/launcher