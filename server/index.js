import 'dotenv/config';
import express from 'express';
import cors from 'cors';
const app=express(); const PORT=Number(process.env.PORT||10000);
app.use(cors({origin:true,methods:["GET","OPTIONS"]}));
app.get("/health",(_req,res)=>res.json({ok:true,service:"nyx-backend",storage:"backblaze-b2-direct"}));
app.listen(PORT,"0.0.0.0",()=>console.log(`NYX backend listening on ${PORT}`));
