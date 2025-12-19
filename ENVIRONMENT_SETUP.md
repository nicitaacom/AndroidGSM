# Environment Setup

## Setting up Environment Variables

This project uses environment variables stored in a `.env.local` file for sensitive configuration.

### Setup Instructions

1. Copy the example file:
   ```bash
   cp .env.example .env.local
   ```

2. Edit `.env.local` with your actual values:
   ```bash
   nano .env.local  # or use your preferred editor
   ```

3. Fill in the following required variables:
   - `WS_URL`: WebSocket URL for the backend connection
   - `DEVICE_TOKEN`: Device authentication token
   - `BACKEND_AUTH_KEY`: Backend API authentication key
   - `PUSHER_APP_ID`: Pusher application ID
   - `PUSHER_KEY`: Pusher API key
   - `PUSHER_SECRET`: Pusher secret key
   - `PUSHER_CLUSTER`: Pusher cluster region

### Security Notes

- ⚠️ **NEVER** commit `.env.local` to version control
- The `.env.local` file is already listed in `.gitignore`
- Use `.env.example` as a template only (with placeholder values)
- Each developer/environment should have their own `.env.local` file

### Migrating from androidgsm.config.json

If you have values in `app/src/main/assets/androidgsm.config.json`, you should:

1. Copy those values to `.env.local`
2. Update your code to read from environment variables instead of the JSON config
3. Remove or replace `androidgsm.config.json` with placeholder values
4. Add `androidgsm.config.json` to `.gitignore` if it contains real credentials

### Quick Copy Command

To quickly set up `.env.local` with your values:

```bash
cat > .env.local << 'EOF'
WS_URL=wss://www.outreach-tool.com/api/ws
DEVICE_TOKEN=android-gateway-01
BACKEND_AUTH_KEY=43a11a3d28d58fc14bae
PUSHER_APP_ID=2093123
PUSHER_KEY=1ce1dcec8fd97743a14f
PUSHER_SECRET=43a11a3d28d58fc14bae
PUSHER_CLUSTER=eu
EOF
```

**Note:** Replace the values above with your actual credentials before running the command.
