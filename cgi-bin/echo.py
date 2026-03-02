import sys
import os

sys.stdout.write('Content-type: text/plain\n\n')
sys.stdout.write('CGI ECHO\n')
if os.environ.get('REQUEST_METHOD') == 'POST':
    sys.stdout.write(sys.stdin.read())
