import socket
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from PIL import Image
from amazon.models import WareHouse
from concurrent.futures import ThreadPoolExecutor
import math

# email address info
SMTP_SERVER = 'smtp.gmail.com:587'
USER_ACCOUNT = {
    'username': 'ece568noreply@gmail.com',
    'password': 'ece568code'
}
pool = ThreadPoolExecutor(10)


def send_email_async(receivers, text):
    pool.submit(send_email, receivers, text)


def send_email(receivers, text):
    msg_root = MIMEMultipart()
    msg_root['Subject'] = "Info from Mini Amazon 568"
    msg_root['To'] = ", ".join(receivers)
    msg_text = MIMEText(text)
    msg_root.attach(msg_text)

    smtp = smtplib.SMTP(SMTP_SERVER)
    smtp.starttls()
    smtp.login(USER_ACCOUNT["username"], USER_ACCOUNT["password"])
    smtp.sendmail(USER_ACCOUNT["username"], receivers, msg_root.as_string())
    smtp.quit()


def save_img(name, data):
    img = Image.open(data)
    img.save("/code/amazon/static/img/%s" % (name), "JPEG", optimize=1)


# calculate the nearest warehouse for the location
def cal_warehouse(x, y):
    whs = WareHouse.objects.all()
    min_id = 1
    min_dest = 65535
    for wh in whs:
        dest = math.sqrt(math.pow(wh.x - x, 2) + math.pow(wh.y - y, 2))
        if dest < min_dest:
            min_dest = dest
            min_id = wh.id
    return min_id


# Tell the daemon to purchase something, which specify by the package id.
# front-end should first store the package into DB and then notify the daemon by sending the id.
def purchase(package_id):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # use port 8888 to communicate with daemon
    client.connect(('daemon', 8888))
    # NOTE: append a \n at the end to become a line
    msg = str(package_id) + '\n'
    client.send(msg.encode('utf-8'))
    # expected response: ack:<package_id>
    data = client.recv(1024)
    data = data.decode()
    res = data.split(":")
    if res[0] == "ack" and res[1] == str(package_id):
        return True
    print('recv:', data)
    return False


if __name__ == '__main__':
    send_email_async(["xiakewei96@gmail.com"], "hello")
