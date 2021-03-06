package it.unica.enrico.models;

import it.unica.enrico.utils.Constants;
import it.unica.enrico.utils.HashUtils;

import java.nio.ByteBuffer;
import java.security.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Blockchain {

    public static final String NO_ONE = "no one";
    public static final Signature NO_ONE_SIGNATURE;
    public static final byte[] NO_ONE_PUB_KEY;
    static {
        try {
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("DSA", "SUN");
            final SecureRandom random = SecureRandom.getInstance("SHA1PRNG", "SUN");
            gen.initialize(512, random);

            final KeyPair pair = gen.generateKeyPair();
            final PrivateKey privateKey = pair.getPrivate();
            NO_ONE_SIGNATURE = Signature.getInstance("SHA1withDSA", "SUN");
            NO_ONE_SIGNATURE.initSign(privateKey);

            final PublicKey publicKey = pair.getPublic();
            NO_ONE_PUB_KEY = publicKey.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static final String GENESIS_NAME  = "Genesis-TheWall";
    protected static final boolean DEBUG = Boolean.getBoolean("debug");
    private static final byte[] INITIAL_HASH= new byte[0];
    private static final Transaction GENESIS_TRANS;
    private static final Block GENESIS_BLOCK;

    static {
        // To start the blockchain, we have an initial transaction which has no inputs and one output which
        // is given to the genesis entity. This is the only transaction which has no inputs.
        final Transaction[] empty = new Transaction[0];
        final Transaction[] output = new Transaction[1];
        final String outputMsg = "Genesis gets 50 coins.";
        output[0] = Transaction.newSignedTransaction(NO_ONE_SIGNATURE, NO_ONE, GENESIS_NAME, outputMsg, 50, empty, empty);

        final String msg = "Genesis transfer.";
        GENESIS_TRANS = Transaction.newSignedTransaction(NO_ONE_SIGNATURE, NO_ONE, GENESIS_NAME, msg, 0, empty, output);

        final ByteBuffer buffer = ByteBuffer.allocate(GENESIS_TRANS.getBufferLength());
        GENESIS_TRANS.toBuffer(buffer);
        buffer.flip();

        final byte[] bytes = buffer.array();
        final byte[] nextHash = Blockchain.getNextHash(Blockchain.INITIAL_HASH, bytes);

        final Transaction[] trans = new Transaction[]{ GENESIS_TRANS };
        GENESIS_BLOCK = new Block(NO_ONE, Blockchain.INITIAL_HASH, nextHash, trans, 0);
        GENESIS_BLOCK.setBloccoConf(true);
    }

    private final List<Block> blockchain= new CopyOnWriteArrayList<Block>();
    private final List<Transaction> transactions = new CopyOnWriteArrayList<Transaction>();
    private final List<Transaction> unused = new CopyOnWriteArrayList<Transaction>();

    private final String                    owner;

    private volatile byte[]                 latestHash          = INITIAL_HASH;

    public Blockchain(String owner) {
        this.owner = owner;
        // transfer initial coins to genesis entity
        this.addBlock(GENESIS_NAME, GENESIS_BLOCK);
    }

    public int getLength() {
        return blockchain.size();
    }

    public Block getBlock(int blockNumber) {
        if (blockNumber>=blockchain.size())
            return null;
        return blockchain.get(blockNumber);
    }

    public List<Transaction> getUnused() {
        return unused;
    }

    public Block getNextBlock(String name, Transaction[] transactions) {
        int length = 0;
        for (Transaction transaction : transactions)
            length += transaction.getBufferLength();
        final byte[] bytes = new byte[length];
        final ByteBuffer bb = ByteBuffer.wrap(bytes);

        for (Transaction transaction : transactions) {
            final ByteBuffer buffer = ByteBuffer.allocate(transaction.getBufferLength());
            transaction.toBuffer(buffer);
            buffer.flip();
            bb.put(buffer.array());
        }

        final byte[] nextHash = getNextHash(latestHash, bytes);
        return (new Block(name, latestHash, nextHash, transactions, this.blockchain.size()));
    }

    public Constants.Status checkHash(Block block) {
        if (block.getDimensione() > this.blockchain.size()) {
            // This block is in the future
            if (DEBUG)
                System.out.println(owner+" found a future block. lengths="+this.blockchain.size()+"\n"+"block={\n"+block.toString()+"\n}");
            return Constants.Status.FUTURE_BLOCK;
        }

        int length = 0;
        for (Transaction transaction : block.getTransazioni())
            length += transaction.getBufferLength();
        final byte[] bytes = new byte[length];
        final ByteBuffer bb = ByteBuffer.wrap(bytes);

        for (Transaction transaction : block.getTransazioni()) {
            final ByteBuffer buffer = ByteBuffer.allocate(transaction.getBufferLength());
            transaction.toBuffer(buffer);
            buffer.flip();
            bb.put(buffer.array());
        }

        // Calculate what I think the next has should be
        final byte[] nextHash = getNextHash(latestHash, bytes);

        // Store the previous and next hash from the block
        final byte[] incomingPrev = block.hashBloccoPrecedente;
        final byte[] incomingHash = block.hash;

        if (!(Arrays.equals(incomingHash, nextHash))) {
            // This block has a different 'next' hash then I expect, someone is out of sync
            if (DEBUG) {
                StringBuilder builder = new StringBuilder();
                builder.append(owner).append("Hash del blocco invalido \n");
                builder.append("confermato? "+block.getBloccoConf()).append("\n");
                builder.append("dimensione: ").append(this.blockchain.size()).append("\n");
                builder.append("l'ultimo hash: ["+ HashUtils.bytesToHex(latestHash)+"]\n");
                builder.append("prossimo: ["+HashUtils.bytesToHex(nextHash)+"]\n");
                builder.append("Prossima dimensione: ").append(block.getDimensione()).append("\n");
                builder.append("Prossimo hash precedente: ["+HashUtils.bytesToHex(incomingPrev)+"]\n");
                builder.append("Prossimo hash: ["+HashUtils.bytesToHex(incomingHash)+"]\n");
                System.err.println(builder.toString());
            }
            return Constants.Status.BAD_HASH;
        }

        return Constants.Status.SUCCESS;
    }

    public Constants.Status addBlock(String dataFrom, Block block) {
        // Already processed this block? Happens if a miner is slow and isn't first to confirm the block
        if (blockchain.contains(block))
            return Constants.Status.DUPLICATE;

        // Check to see if the block's hash is what I expect
        final Constants.Status status = checkHash(block);
        if (status != Constants.Status.SUCCESS)
            return status;

        // Get the aggregate transaction for processing
        for (Transaction transaction : block.getTransazioni()) {
            // Remove the inputs from the unused pool
            for (Transaction t : transaction.inputs) {
                boolean exists = unused.remove(t);
                if (exists == false) {
                    if (DEBUG)
                        System.err.println(owner+" Bad inputs in block from '"+dataFrom+"'. block={\n"+block.toString()+"\n}");
                    return Constants.Status.BAD_INPUTS;
                }
            }
        }

        // Add outputs to unused pool
        for (Transaction transaction : block.getTransazioni()) {
            for (Transaction t : transaction.outputs)
                unused.add(t);
        }

        // Update the hash and add the new transaction to the list
        final byte[] prevHash = latestHash;
        final byte[] nextHash = block.hash;
        blockchain.add(block);
        for (Transaction transaction : block.getTransazioni())
            transactions.add(transaction);
        latestHash = nextHash;

        if (DEBUG) {
            final String prev = HashUtils.bytesToHex(prevHash);
            final String next = HashUtils.bytesToHex(nextHash);
            final StringBuilder builder = new StringBuilder();
            builder.append(owner).append(" Hash aggiornato").append(" msg da: '"+dataFrom+"'").append(" blocco da: '"+block.getProvenienza()+"'\n");
            builder.append("Dimensione blockchain: ").append(blockchain.size()).append("\n");
            builder.append("Transazioni: [\n");
            for (Transaction t : block.getTransazioni()) {
                builder.append(t.toString()).append("\n");
            }
            builder.append("]\n");
            builder.append("precedente: [").append(prev).append("]\n");
            builder.append("prossimo: [").append(next).append("]\n");
            System.err.println(builder.toString());
        }

        return Constants.Status.SUCCESS;
    }

    public long getBalance(String name) {
        long result = 0;
        for (Transaction t : transactions) {
            // Remove the inputs
            for (Transaction c : t.inputs) {
                if (name.equals(c.getDestinatario()))
                    result -= c.getValore();
            }
            // Add the outputs
            for (Transaction c : t.outputs) {
                if (name.equals(c.getDestinatario()))
                    result += c.getValore();
            }
        }
        return result;
    }

    public static final byte[] getNextHash(byte[] hash, byte[] bytes) {
        final StringBuilder builder = new StringBuilder();
        builder.append(HashUtils.bytesToHex(hash)).append(HashUtils.bytesToHex(bytes));
        final String string = builder.toString();
        final byte[] output = HashUtils.calculateSha256(string);
        return output;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Blockchain))
            return false;
        final Blockchain other = (Blockchain) o;
        for (Block b : blockchain) {
            if (!(other.blockchain.contains(b)))
                return false;
        }
        for (Transaction t : transactions) {
            if (!(other.transactions.contains(t)))
                return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("Hash: [").append(HashUtils.bytesToHex(latestHash)).append("]\n");
        builder.append("gli input: {").append("\n");
        for (Transaction c : transactions) {
            for (Transaction i : c.inputs)
                builder.append('\t').append(i.getValore()).append(" da '").append(i.getMittente()).append("' -> a '").append(i.getDestinatario()).append("'\n");
            builder.append("}\n");
        }
        builder.append("gli output: {").append("\n");
        for (Transaction c : transactions) {
            for (Transaction i : c.outputs)
                builder.append('\t').append(i.getValore()).append(" da '").append(i.getMittente()).append("' -> a '").append(i.getDestinatario()).append("'\n");
            builder.append("}\n");
        }
        return builder.toString();
    }
}